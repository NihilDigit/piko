@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.media3.common.util.UnstableApi::class,
)

package dev.piko.ui.components

import kotlin.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.piko.PikoApplication
import dev.piko.data.repository.PlayableMediaInfo
import io.github.nihildigit.pikpak.FileStat
import java.util.Locale

fun formatTimeMs(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) {
        String.format(Locale.getDefault(), "%02d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", m, s)
    }
}

/**
 * 视频段落下载配置弹窗：
 * 提供起点与终点两处画面的实时帧预览，微调步进控制与时长/文件体积估算，
 * 确认后交由 SDK 的 8 连接并发分块滑动窗口机制进行段落流式写入。
 */
@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun SegmentDownloadSheet(
    file: FileStat,
    onDismiss: () -> Unit,
    onConfirmDownload: (startByte: Long, lengthBytes: Long, timeLabel: String, startMs: Long, endMs: Long, streamUrl: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val mediaRepo = PikoApplication.instance.mediaRepository

    var mediaInfo by remember { mutableStateOf<PlayableMediaInfo?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    var totalDurationMs by remember { mutableLongStateOf(0L) }
    var startPosMs by remember { mutableLongStateOf(0L) }
    var endPosMs by remember { mutableLongStateOf(0L) }

    // 初始化获取视频直链与时长
    LaunchedEffect(file.id) {
        isLoading = true
        val res = mediaRepo.prepareMedia(file.id)
        isLoading = false
        res.onSuccess { info ->
            mediaInfo = info
            val dur = info.durationSeconds * 1000L
            if (dur > 0) {
                totalDurationMs = dur
                startPosMs = 0L
                endPosMs = minOf(dur, 60_000L) // 默认前 1 分钟段落
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // 顶栏标题
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.ContentCut,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "下载指定段落",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Outlined.Close, contentDescription = "关闭")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                val currentUrl = mediaInfo?.currentUrl.orEmpty()

                // 起始点与结束点画面预览区域 (并排双预览)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // 起点预览卡片
                    Column(modifier = Modifier.weight(1f)) {
                        PreviewCard(
                            label = "起点: ${formatTimeMs(startPosMs)}",
                            url = currentUrl,
                            positionMs = startPosMs,
                            onDurationKnown = { dur ->
                                if (totalDurationMs <= 0 && dur > 0) {
                                    totalDurationMs = dur
                                    if (endPosMs == 0L) endPosMs = minOf(dur, 60_000L)
                                }
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StepChip(label = "-1s", modifier = Modifier.weight(1f)) {
                                startPosMs = (startPosMs - 1000L).coerceAtLeast(0L)
                            }
                            StepChip(label = "+1s", modifier = Modifier.weight(1f)) {
                                startPosMs = (startPosMs + 1000L).coerceAtMost(endPosMs - 500L)
                            }
                        }
                    }

                    // 终点预览卡片
                    Column(modifier = Modifier.weight(1f)) {
                        PreviewCard(
                            label = "终点: ${formatTimeMs(endPosMs)}",
                            url = currentUrl,
                            positionMs = endPosMs,
                            onDurationKnown = { dur ->
                                if (totalDurationMs <= 0 && dur > 0) {
                                    totalDurationMs = dur
                                    if (endPosMs == 0L) endPosMs = minOf(dur, 60_000L)
                                }
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StepChip(label = "-1s", modifier = Modifier.weight(1f)) {
                                endPosMs = (endPosMs - 1000L).coerceAtLeast(startPosMs + 500L)
                            }
                            StepChip(label = "+1s", modifier = Modifier.weight(1f)) {
                                endPosMs = (endPosMs + 1000L).coerceAtMost(totalDurationMs)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 滑动区间选择器
                if (totalDurationMs > 0) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "时间轴微调范围",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "总时长: ${formatTimeMs(totalDurationMs)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        RangeSlider(
                            value = startPosMs.toFloat()..endPosMs.toFloat(),
                            onValueChange = { range ->
                                startPosMs = range.start.toLong().coerceIn(0L, totalDurationMs)
                                endPosMs = range.endInclusive.toLong().coerceIn(startPosMs + 500L, totalDurationMs)
                            },
                            valueRange = 0f..totalDurationMs.toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 段落信息汇总卡片
                val clipDurationMs = (endPosMs - startPosMs).coerceAtLeast(0L)
                val estimatedRatio = if (totalDurationMs > 0) (clipDurationMs.toDouble() / totalDurationMs.toDouble()) else 0.0
                val estimatedBytes = (file.sizeBytes * estimatedRatio).toLong().coerceIn(0L, file.sizeBytes)

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Timer,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "截取时长: ${formatTimeMs(clipDurationMs)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }

                            Text(
                                text = "预计大小: ~ ${estimatedBytes.toReadableSize()}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 操作按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("取消")
                    }

                    Button(
                        onClick = {
                            val startRatio = if (totalDurationMs > 0) (startPosMs.toDouble() / totalDurationMs.toDouble()) else 0.0
                            val endRatio = if (totalDurationMs > 0) (endPosMs.toDouble() / totalDurationMs.toDouble()) else 1.0
                            val startByte = (startRatio * file.sizeBytes).toLong().coerceIn(0L, file.sizeBytes)
                            val endByte = (endRatio * file.sizeBytes).toLong().coerceIn(startByte, file.sizeBytes)
                            val lengthBytes = (endByte - startByte).coerceAtLeast(1024L)
                            val label = "${formatTimeMs(startPosMs)}_${formatTimeMs(endPosMs)}"

                            onConfirmDownload(startByte, lengthBytes, label, startPosMs, endPosMs, mediaInfo?.currentUrl)
                        },
                        modifier = Modifier.weight(2f),
                    ) {
                        Icon(imageVector = Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("下载指定段落")
                    }
                }
            }
        }
    }
}

/**
 * 带有实时帧定位与加载态的预览卡片
 */
@OptIn(UnstableApi::class)
@Composable
private fun PreviewCard(
    label: String,
    url: String,
    positionMs: Long,
    onDurationKnown: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val previewPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = false
        }
    }

    LaunchedEffect(url) {
        if (url.isNotBlank()) {
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        onDurationKnown(previewPlayer.duration.coerceAtLeast(0L))
                    }
                }
            }
            previewPlayer.addListener(listener)
            previewPlayer.setMediaItem(MediaItem.fromUri(url))
            previewPlayer.prepare()
        }
    }

    LaunchedEffect(positionMs) {
        previewPlayer.seekTo(positionMs)
    }

    DisposableEffect(previewPlayer) {
        onDispose {
            previewPlayer.release()
        }
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(MaterialTheme.colorScheme.surfaceDim),
                contentAlignment = Alignment.Center,
            ) {
                if (url.isNotBlank()) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = previewPlayer
                                useController = false
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
