package dev.piko.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.DiskSpace
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.common_cancel
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.exp

private val CarouselCardScrim = Brush.verticalGradient(
    0.35f to Color.Transparent,
    1.0f to Color(0xC7000000),
)

/**
 * 开启「每次下载前选择位置」时的轻量应用内下载确认框，替代直接弹出系统文件夹选择器。
 * 单项展示缩略图媒体卡，多项采用 M3 Expressive [HorizontalMultiBrowseCarousel] 横向浏览（桌面端支持纵向滚轮直接横向滚动），
 * 配合保存位置卡片与「默认保存到此位置，不再每次询问」勾选框。
 */
@Composable
internal fun DownloadConfirmDialog(
    request: DownloadConfirmRequest,
    onPickLocation: () -> Unit,
    onConfirm: (saveAsDefault: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var saveAsDefault by remember(request) { mutableStateOf(false) }
    val spoilerBlurred by LocalPikoServices.current.preferences.spoilerBlurFlow.collectAsState(initial = false)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(request) {
        focusRequester.requestFocus()
    }

    PikoDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Download, contentDescription = null) },
        title = {
            Text(
                tr(
                    "下载到本地",
                    "Download to device",
                    "端末にダウンロード",
                    "기기에 다운로드",
                    "下載至本機",
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .focusRequester(focusRequester)
                    .focusable()
                    .onDialogEnterConfirm(enabled = true) { onConfirm(saveAsDefault) },
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DownloadItemSummarySection(
                    summary = request.summary,
                    isSpoilerBlurred = spoilerBlurred,
                )
                DownloadLocationCard(
                    targetDirectory = request.targetDirectory,
                    onPickLocation = onPickLocation,
                )
                RememberDownloadLocationRow(
                    checked = saveAsDefault,
                    onCheckedChange = { saveAsDefault = it },
                )
            }
        },
        confirmButton = {
            PikoDialogConfirm(
                label = tr("下载", "Download", "ダウンロード", "다운로드", "下載"),
                onClick = { onConfirm(saveAsDefault) },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.common_cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadItemSummarySection(
    summary: DownloadItemSummary,
    isSpoilerBlurred: Boolean,
) {
    if (summary.items.size >= 2) {
        DownloadBatchCarouselSection(
            summary = summary,
            isSpoilerBlurred = isSpoilerBlurred,
        )
        return
    }

    val singleItem = summary.items.firstOrNull()
    val metaParts = remember(summary, singleItem) {
        buildList {
            if (summary.isFolderOnly) {
                add(tr("文件夹", "Folder", "フォルダー", "폴더", "資料夾"))
            } else {
                singleItem?.visual?.extensionLabel()?.let { add(it) }
                summary.timeRangeLabel?.let { add(formatSegmentRange(it)) }
                if (summary.totalKnownBytes > 0L) {
                    add(summary.totalKnownBytes.toReadableSize())
                }
            }
        }
    }

    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainerLow,
        border = if (isEInk) BorderStroke(1.dp, colors.outline) else null,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (singleItem != null) {
                FileListLeadingVisual(
                    file = singleItem.visual,
                    size = 52.dp,
                    isSpoilerBlurred = isSpoilerBlurred,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = summary.primaryText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (metaParts.isNotEmpty()) {
                    MetaRow(
                        parts = metaParts,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadBatchCarouselSection(
    summary: DownloadItemSummary,
    isSpoilerBlurred: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val isEInk = LocalAppearance.current.isEInk
    val carouselState = rememberCarouselState { summary.items.size }
    val wheelScope = rememberCoroutineScope()
    var pendingWheelPx by remember(carouselState) { mutableFloatStateOf(0f) }
    var wheelAnimJob by remember(carouselState) { mutableStateOf<Job?>(null) }
    val countLabel = remember(summary.itemCount, summary.folderCount) {
        if (summary.folderCount in 1 until summary.itemCount) {
            tr(
                "共 ${summary.itemCount} 项（含 ${summary.folderCount} 个文件夹）",
                "${summary.itemCount} items (${summary.folderCount} folders)",
                "計 ${summary.itemCount} 項目（フォルダー ${summary.folderCount} 件を含む）",
                "총 ${summary.itemCount}개 항목(폴더 ${summary.folderCount}개 포함)",
                "共 ${summary.itemCount} 項（含 ${summary.folderCount} 個資料夾）",
            )
        } else {
            tr(
                "共 ${summary.itemCount} 项",
                "${summary.itemCount} items",
                "計 ${summary.itemCount} 項目",
                "총 ${summary.itemCount}개 항목",
                "共 ${summary.itemCount} 項",
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = countLabel,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (summary.totalKnownBytes > 0L) {
                Text(
                    text = summary.totalKnownBytes.toReadableSize(),
                    style = MaterialTheme.typography.labelMedium.tabular,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        HorizontalMultiBrowseCarousel(
            state = carouselState,
            preferredItemWidth = 148.dp,
            itemSpacing = 8.dp,
            flingBehavior = CarouselDefaults.noSnapFlingBehavior(),
            modifier = Modifier
                .fillMaxWidth()
                .height(108.dp)
                .pointerInput(carouselState) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type == PointerEventType.Scroll) {
                                val delta = event.changes.fold(Offset.Zero) { acc, c -> acc + c.scrollDelta }
                                val raw = if (abs(delta.x) > abs(delta.y)) delta.x else delta.y
                                if (raw != 0f) {
                                    val canAdvance = (raw > 0f && carouselState.canScrollForward) ||
                                        (raw < 0f && carouselState.canScrollBackward)
                                    if (canAdvance) {
                                        event.changes.forEach { it.consume() }
                                        if (abs(raw) < 0.45f) {
                                            carouselState.dispatchRawDelta(raw * 64f)
                                        } else {
                                            pendingWheelPx = (pendingWheelPx + raw * 136f).coerceIn(-1200f, 1200f)
                                            if (wheelAnimJob?.isActive != true) {
                                                wheelAnimJob = wheelScope.launch {
                                                    var lastNanos = 0L
                                                    carouselState.scroll {
                                                        while (abs(pendingWheelPx) > 0.5f) {
                                                            val frameNanos = withFrameNanos { it }
                                                            val dtSec = if (lastNanos == 0L) {
                                                                1f / 60f
                                                            } else {
                                                                ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                                                            }
                                                            lastNanos = frameNanos
                                                            val step = pendingWheelPx * (1f - exp(-dtSec * 18f))
                                                            val actual = scrollBy(step)
                                                            pendingWheelPx -= step
                                                            if (abs(actual) < 0.05f) {
                                                                pendingWheelPx = 0f
                                                                break
                                                            }
                                                        }
                                                        pendingWheelPx = 0f
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
        ) { index ->
            val item = summary.items[index]
            val shape = MaterialTheme.shapes.large
            val cardModifier = Modifier
                .height(108.dp)
                .maskClip(shape)
                .then(
                    if (isEInk) Modifier.maskBorder(BorderStroke(1.dp, Color.Black), shape) else Modifier,
                )
            DownloadCarouselTile(
                item = item,
                isSpoilerBlurred = isSpoilerBlurred,
                modifier = cardModifier,
            )
        }
    }
}

@Composable
private fun DownloadCarouselTile(
    item: DownloadPreviewItem,
    isSpoilerBlurred: Boolean,
    modifier: Modifier = Modifier,
) {
    val isEInk = LocalAppearance.current.isEInk
    var coverFailed by remember(item.visual.thumbnail) { mutableStateOf(false) }
    val activeCover = if (!isEInk && !coverFailed) item.visual.thumbnail else null
    val (placeholderBg, placeholderFg) = item.visual.placeholderColors()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(placeholderBg),
    ) {
        if (activeCover != null) {
            SpoilerThumbnail(
                model = activeCover,
                isBlurred = isSpoilerBlurred,
                blur = PosterSpoilerBlur,
                showBlurIcon = false,
                stableId = item.visual.stableId,
                onError = { coverFailed = true },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(CarouselCardScrim),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = item.visual.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.let { sub ->
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelSmall.tabular,
                        color = Color.White.copy(alpha = 0.82f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        } else {
            Icon(
                imageVector = item.visual.watermarkIcon(),
                contentDescription = null,
                tint = placeholderFg.copy(alpha = if (isEInk) 0.85f else 0.42f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 10.dp)
                    .size(34.dp),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = item.visual.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = placeholderFg,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                item.subtitle?.let { sub ->
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelSmall.tabular,
                        color = placeholderFg.copy(alpha = if (isEInk) 1f else 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * 供 [DownloadConfirmDialog] 与 [DownloadQualityDialog] 共用的保存位置卡片。
 * 顶部单行固定承载「保存到」标签与异步查询到的磁盘可用空间，避免对话框内容高度跳动。
 */
@Composable
internal fun DownloadLocationCard(
    targetDirectory: String,
    onPickLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val downloadLocation = LocalPikoPlatform.current.downloadLocation
    val isEInk = LocalAppearance.current.isEInk
    val colors = MaterialTheme.colorScheme

    val resolvedPath by produceState(
        initialValue = if (targetDirectory.startsWith("content:")) {
            tr("已选择的文件夹", "Selected folder", "選択したフォルダー", "선택한 폴더", "已選擇的資料夾")
        } else {
            downloadLocation.displayName(targetDirectory)
        },
        key1 = targetDirectory,
        key2 = downloadLocation,
    ) {
        value = if (targetDirectory.startsWith("content:")) {
            withContext(Dispatchers.IO) { downloadLocation.displayName(targetDirectory) }
        } else {
            downloadLocation.displayName(targetDirectory)
        }
    }

    val diskSpace by produceState<DiskSpace?>(
        initialValue = null,
        key1 = targetDirectory,
        key2 = downloadLocation,
    ) {
        value = withContext(Dispatchers.IO) { downloadLocation.diskSpace(targetDirectory) }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tr("保存到", "Save to", "保存先", "저장 위치", "儲存至"),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
            diskSpace?.let { space ->
                Text(
                    text = tr(
                        "可用 ${space.freeBytes.toReadableSize()}",
                        "${space.freeBytes.toReadableSize()} free",
                        "空き ${space.freeBytes.toReadableSize()}",
                        "${space.freeBytes.toReadableSize()} 사용 가능",
                        "可用 ${space.freeBytes.toReadableSize()}",
                    ),
                    style = MaterialTheme.typography.labelMedium.tabular,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = colors.surfaceContainerLow,
            border = if (isEInk) BorderStroke(1.dp, colors.outline) else null,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = colors.onSurfaceVariant,
                )
                Text(
                    text = resolvedPath,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = onPickLocation,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 36.dp),
                ) {
                    Text(
                        text = tr("更改", "Change", "変更", "변경", "變更"),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 勾上后将当前保存位置设为默认，并关闭「每次下载前选择位置」。 */
@Composable
internal fun RememberDownloadLocationRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            text = tr(
                "默认保存到此位置，不再每次询问",
                "Save here by default and don't ask each time",
                "既定の保存先にして今後は確認しない",
                "기본 저장 위치로 설정하고 다시 묻지 않기",
                "預設儲存至此位置，不再每次詢問",
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 气泡阶段（onKeyEvent）拦截未修饰的 Enter 提交对话框：焦点位于「更改」按钮、复选框或「取消」按钮时，
 * 子控件优先消费回车；仅当焦点停留在对话框容器本身时才触发确认。
 */
internal fun Modifier.onDialogEnterConfirm(
    enabled: Boolean,
    onConfirm: () -> Unit,
): Modifier = onKeyEvent { event ->
    if (!enabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
    val unmodified = !event.isAltPressed && !event.isCtrlPressed && !event.isMetaPressed && !event.isShiftPressed
    if (unmodified && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
        onConfirm()
        true
    } else {
        false
    }
}

internal fun formatSegmentRange(label: String): String {
    val parts = label.split('_').filter { it.isNotBlank() }
    return if (parts.size == 2) "${parts[0]} - ${parts[1]}" else label
}
