package dev.piko.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.shared.media.PikoMediaRepository
import io.github.composefluent.FluentTheme
import io.github.composefluent.component.ContentDialog
import io.github.composefluent.component.ContentDialogButton
import io.github.composefluent.component.Icon
import io.github.composefluent.component.ProgressRing
import io.github.composefluent.component.ProgressRingSize
import io.github.composefluent.component.Slider
import io.github.composefluent.component.Text
import io.github.composefluent.icons.Icons
import io.github.composefluent.icons.regular.Cut
import io.github.composefluent.icons.regular.Dismiss
import io.github.composefluent.surface.Card
import io.github.nihildigit.pikpak.FileStat
import kotlinx.coroutines.delay
import org.openani.mediamp.ExperimentalMediampApi
import org.openani.mediamp.compose.MediampPlayerSurface
import org.openani.mediamp.compose.rememberMediampPlayer
import org.openani.mediamp.isLoadingOrBuffering
import org.openani.mediamp.playUri

/**
 * 视频段落下载对话框：与 Android SegmentDownloadSheet 同构——
 * 起点/终点双路实时帧预览、±1s 微调、双滑杆区间、时长与体积汇总、关键帧对齐提示。
 * 确认只回传毫秒区间，取新鲜直链与入队由调用方做（直链有时效）。
 */
@Composable
fun SegmentDialog(
    file: FileStat,
    mediaRepository: PikoMediaRepository,
    onDismiss: () -> Unit,
    onConfirm: (startMs: Long, endMs: Long, timeRangeLabel: String) -> Unit,
) {
    var streamUrl by remember { mutableStateOf("") }
    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var startPosMs by remember { mutableLongStateOf(0L) }
    var endPosMs by remember { mutableLongStateOf(0L) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(file.id) {
        isLoading = true
        mediaRepository.prepareMedia(file.id).onSuccess { info ->
            streamUrl = info.currentUrl
            val dur = info.durationSeconds * 1000L
            if (dur > 0) {
                totalDurationMs = dur
                startPosMs = 0L
                endPosMs = minOf(dur, 60_000L)
            }
        }
        isLoading = false
    }

    ContentDialog(
        title = "下载指定段落",
        visible = true,
        primaryButtonText = "下载指定段落",
        closeButtonText = "取消",
        onButtonClick = { button ->
            when (button) {
                ContentDialogButton.Primary -> {
                    if (totalDurationMs > 0 && endPosMs > startPosMs) {
                        onConfirm(
                            startPosMs,
                            endPosMs,
                            "${formatSegmentTime(startPosMs)}-${formatSegmentTime(endPosMs)}",
                        )
                    }
                }
                ContentDialogButton.Close -> onDismiss()
                else -> {}
            }
        },
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Regular.Cut,
                        contentDescription = null,
                        tint = FluentTheme.colors.fillAccent.default,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = file.name,
                        style = FluentTheme.typography.body,
                        color = FluentTheme.colors.text.text.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        ProgressRing(size = ProgressRingSize.Large)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            SegmentPreviewCard(
                                label = "起点 ${formatSegmentTime(startPosMs)}",
                                url = streamUrl,
                                positionMs = startPosMs,
                                onDurationKnown = { dur ->
                                    if (totalDurationMs <= 0 && dur > 0) {
                                        totalDurationMs = dur
                                        if (endPosMs == 0L) endPosMs = minOf(dur, 60_000L)
                                    }
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                StepChip("-1s", Modifier.weight(1f)) {
                                    startPosMs = (startPosMs - 1000L).coerceAtLeast(0L)
                                }
                                StepChip("+1s", Modifier.weight(1f)) {
                                    startPosMs = (startPosMs + 1000L)
                                        .coerceAtMost((endPosMs - 500L).coerceAtLeast(0L))
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            SegmentPreviewCard(
                                label = "终点 ${formatSegmentTime(endPosMs)}",
                                url = streamUrl,
                                positionMs = endPosMs,
                                onDurationKnown = { dur ->
                                    if (totalDurationMs <= 0 && dur > 0) {
                                        totalDurationMs = dur
                                        if (endPosMs == 0L) endPosMs = minOf(dur, 60_000L)
                                    }
                                },
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                StepChip("-1s", Modifier.weight(1f)) {
                                    endPosMs = (endPosMs - 1000L)
                                        .coerceAtLeast(startPosMs + 500L)
                                }
                                StepChip("+1s", Modifier.weight(1f)) {
                                    endPosMs = (endPosMs + 1000L).coerceAtMost(totalDurationMs)
                                }
                            }
                        }
                    }

                    if (totalDurationMs > 0) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "起点",
                                    style = FluentTheme.typography.caption,
                                    color = FluentTheme.colors.text.text.secondary,
                                )
                                Text(
                                    "总时长 ${formatSegmentTime(totalDurationMs)}",
                                    style = FluentTheme.typography.caption,
                                    color = FluentTheme.colors.fillAccent.default,
                                )
                            }
                            Slider(
                                value = startPosMs.toFloat(),
                                onValueChange = {
                                    startPosMs = it.toLong()
                                        .coerceIn(0L, (endPosMs - 500L).coerceAtLeast(0L))
                                },
                                valueRange = 0f..totalDurationMs.toFloat(),
                                tooltipContent = {},
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "终点",
                                    style = FluentTheme.typography.caption,
                                    color = FluentTheme.colors.text.text.secondary,
                                )
                            }
                            Slider(
                                value = endPosMs.toFloat(),
                                onValueChange = {
                                    endPosMs = it.toLong()
                                        .coerceIn(startPosMs + 500L, totalDurationMs)
                                },
                                valueRange = 0f..totalDurationMs.toFloat(),
                                tooltipContent = {},
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    val clipDurationMs = (endPosMs - startPosMs).coerceAtLeast(0L)
                    val estimatedRatio = if (totalDurationMs > 0) {
                        clipDurationMs.toDouble() / totalDurationMs.toDouble()
                    } else 0.0
                    val estimatedBytes = (file.sizeBytes * estimatedRatio).toLong()
                        .coerceIn(0L, file.sizeBytes)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "截取时长 ${formatSegmentTime(clipDurationMs)}",
                                    style = FluentTheme.typography.body,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "预计 ~${formatBytes(estimatedBytes)}",
                                    style = FluentTheme.typography.body,
                                    color = FluentTheme.colors.text.text.secondary,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "原画质无损抽取（MP4 容器），免重新编码。为防止首帧花屏，" +
                                    "实际起点自动对齐至前序同步关键帧（可能提前数秒）。",
                                style = FluentTheme.typography.caption,
                                color = FluentTheme.colors.text.text.secondary,
                            )
                        }
                    }
                }
            }
        },
    )
}

/** 起点/终点各一路静音预览：定位到指定毫秒抽帧，不自动播。 */
@Composable
@OptIn(ExperimentalMediampApi::class)
private fun SegmentPreviewCard(
    label: String,
    url: String,
    positionMs: Long,
    onDurationKnown: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val previewPlayer = rememberMediampPlayer()
    val playerState by previewPlayer.state.collectAsState()

    LaunchedEffect(url) {
        if (url.isNotBlank()) previewPlayer.playUri(url, playWhenReady = false)
    }
    LaunchedEffect(positionMs) {
        previewPlayer.seekTo(positionMs)
    }
    DisposableEffect(previewPlayer) {
        onDispose { previewPlayer.close() }
    }
    LaunchedEffect(previewPlayer) {
        while (true) {
            previewPlayer.mediaProperties.value?.durationMillis?.let(onDurationKnown)
            delay(250)
        }
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (url.isNotBlank()) {
                    MediampPlayerSurface(previewPlayer, Modifier.fillMaxSize())
                    if (playerState.isLoadingOrBuffering) {
                        ProgressRing(modifier = Modifier.background(Color.Transparent))
                    }
                } else {
                    ProgressRing()
                }
            }
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = FluentTheme.typography.caption,
                    fontWeight = FontWeight.SemiBold,
                    color = FluentTheme.colors.text.text.secondary,
                )
            }
        }
    }
}

@Composable
private fun StepChip(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(FluentTheme.colors.subtleFill.secondary)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = FluentTheme.typography.caption,
            fontWeight = FontWeight.Medium,
            color = FluentTheme.colors.text.text.secondary,
        )
    }
}
