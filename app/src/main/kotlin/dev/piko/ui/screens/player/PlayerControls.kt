package dev.piko.ui.screens.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeGesturesPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.openani.mediamp.features.AspectRatioMode
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

/**
 * 播放器顶部控制栏：返回、标题、画面比例、清晰度、倍速入口。
 */
@Composable
internal fun PlayerTopBar(
    title: String,
    isLandscape: Boolean,
    isLocalPlayback: Boolean,
    aspectRatioMode: AspectRatioMode?,
    qualityOptions: List<String>,
    currentQuality: String?,
    showSpeedEntry: Boolean,
    onBackClick: () -> Unit,
    onAspectRatioChange: (AspectRatioMode) -> Unit,
    onQualityChange: (String) -> Unit,
    onSpeedClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAspectMenu by remember { mutableStateOf(false) }
    var showQualityMenu by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Black.copy(alpha = 0.85f),
                        Color.Black.copy(alpha = 0.45f),
                        Color.Transparent,
                    ),
                ),
            )
            .padding(
                horizontal = if (isLandscape) 36.dp else 16.dp,
                vertical = if (isLandscape) 12.dp else 28.dp,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isLocalPlayback) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(
                        text = "本地播放",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.weight(1f))

            // 后端不支持画面比例时整个入口隐藏，避免点了没反应
            if (aspectRatioMode != null) {
                Box {
                    IconButton(onClick = { showAspectMenu = true }) {
                        Icon(Icons.Filled.AspectRatio, contentDescription = "画面比例", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = showAspectMenu,
                        onDismissRequest = { showAspectMenu = false },
                    ) {
                        aspectRatioLabels.forEach { (mode, label) ->
                            DropdownMenuItem(
                                text = { Text(if (mode == aspectRatioMode) "$label ·" else label) },
                                onClick = {
                                    showAspectMenu = false
                                    onAspectRatioChange(mode)
                                },
                            )
                        }
                    }
                }
            }

            if (qualityOptions.isNotEmpty()) {
                Box {
                    IconButton(onClick = { showQualityMenu = true }) {
                        Icon(Icons.Outlined.HighQuality, contentDescription = "清晰度", tint = Color.White)
                    }
                    DropdownMenu(
                        expanded = showQualityMenu,
                        onDismissRequest = { showQualityMenu = false },
                    ) {
                        qualityOptions.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(if (option == currentQuality) "$option ·" else option) },
                                onClick = {
                                    showQualityMenu = false
                                    onQualityChange(option)
                                },
                            )
                        }
                    }
                }
            }

            if (showSpeedEntry) {
                IconButton(onClick = onSpeedClick) {
                    Icon(Icons.Outlined.Speed, contentDescription = "倍速", tint = Color.White)
                }
            }
        }
    }
}

/**
 * 播放器底部控制栏：播放控制、时间、倍速回显、全屏切换与双轨进度条。
 */
@Composable
internal fun PlayerBottomBar(
    isPlaying: Boolean,
    isLandscape: Boolean,
    positionMillis: Long,
    durationMillis: Long,
    bufferedFraction: Float,
    playbackSpeed: Float,
    speedSupported: Boolean,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSpeedClick: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.5f),
                        Color.Black.copy(alpha = 0.9f),
                    ),
                ),
            )
            .padding(
                horizontal = if (isLandscape) 36.dp else 12.dp,
                vertical = if (isLandscape) 14.dp else 16.dp,
            )
            .safeGesturesPadding(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.size(if (isLandscape) 48.dp else 42.dp),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(if (isLandscape) 30.dp else 28.dp),
                        )
                    }
                    IconButton(
                        onClick = { onSeekTo((positionMillis - SEEK_STEP_MILLIS).coerceAtLeast(0L)) },
                        modifier = Modifier.size(if (isLandscape) 48.dp else 36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Replay10,
                            contentDescription = "后退 10 秒",
                            tint = Color.White,
                            modifier = Modifier.size(if (isLandscape) 24.dp else 20.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            val limit = durationMillis.coerceAtLeast(0L)
                            onSeekTo((positionMillis + SEEK_STEP_MILLIS).coerceAtMost(limit))
                        },
                        modifier = Modifier.size(if (isLandscape) 48.dp else 36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Forward10,
                            contentDescription = "前进 10 秒",
                            tint = Color.White,
                            modifier = Modifier.size(if (isLandscape) 24.dp else 20.dp),
                        )
                    }

                    Spacer(Modifier.width(if (isLandscape) 10.dp else 6.dp))

                    val timeStyle = if (isLandscape) {
                        MaterialTheme.typography.labelLarge
                    } else {
                        MaterialTheme.typography.labelMedium
                    }
                    Text(
                        text = formatTime(positionMillis),
                        style = timeStyle,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Text(
                        text = " / ${formatTime(durationMillis)}",
                        style = timeStyle,
                        color = Color.White.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (speedSupported) {
                        Surface(
                            onClick = onSpeedClick,
                            shape = CircleShape,
                            color = Color.White.copy(alpha = 0.18f),
                            modifier = Modifier.padding(end = 4.dp),
                        ) {
                            Text(
                                text = String.format(Locale.US, "%.2fx", playbackSpeed),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                    IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = if (isLandscape) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                            contentDescription = if (isLandscape) "退出全屏" else "全屏",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))

            VideoDualTrackScrubber(
                positionMillis = positionMillis,
                bufferedFraction = bufferedFraction,
                durationMillis = durationMillis,
                onSeek = onSeekTo,
            )
        }
    }
}

/**
 * 缓冲与播放各占一轨的进度条。
 *
 * 轨道自绘，Slider 只保留 thumb 并透明化两条内置轨道：Slider 单轨画不出缓冲进度，
 * 而自绘的轨道拿不到 Slider 的拖拽手感。
 */
@Composable
private fun VideoDualTrackScrubber(
    positionMillis: Long,
    bufferedFraction: Float,
    durationMillis: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMillis by remember { mutableLongStateOf(0L) }

    val activePosition = if (isDragging) dragPositionMillis else positionMillis
    val playedFraction = if (durationMillis > 0) {
        (activePosition.toFloat() / durationMillis).coerceIn(0f, 1f)
    } else {
        0f
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.22f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(bufferedFraction.coerceIn(0f, 1f))
                    .background(Color.White.copy(alpha = 0.45f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(playedFraction)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }

        Slider(
            value = playedFraction,
            onValueChange = { fraction ->
                isDragging = true
                dragPositionMillis = (fraction * durationMillis).toLong()
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek(dragPositionMillis)
            },
            enabled = durationMillis > 0,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        if (isDragging) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.75f),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Text(
                    text = formatTime(dragPositionMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * 倍速调节浮层：0.5 ~ 3.5，步进 0.01。
 */
@Composable
internal fun PlaybackSpeedDialog(
    speed: Float,
    isLandscape: Boolean,
    onSpeedChange: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isLandscape) 0.52f else 0.88f)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF1C1B1F).copy(alpha = 0.96f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier
                    .padding(22.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "播放倍速",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(
                        onClick = { onSpeedChange(1.0f) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "重置 1.0x",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = String.format(Locale.US, "%.2fx", speed),
                    style = MaterialTheme.typography.displaySmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    ),
                )

                Spacer(Modifier.height(14.dp))

                Slider(
                    value = speed,
                    onValueChange = { raw -> onSpeedChange(round(raw * 100f) / 100f) },
                    valueRange = MIN_SPEED..MAX_SPEED,
                    steps = 0,
                    modifier = Modifier.fillMaxWidth(),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                    ),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf("0.5x", "2.0x", "3.5x").forEach { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    presetSpeeds.forEach { preset ->
                        val selected = abs(speed - preset) < 0.005f
                        Surface(
                            onClick = { onSpeedChange(preset) },
                            shape = CircleShape,
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.White.copy(alpha = 0.12f)
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "${preset}x",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        Color.White
                                    },
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                ) {
                    Text("完成")
                }
            }
        }
    }
}

internal const val SEEK_STEP_MILLIS = 10_000L
internal const val MIN_SPEED = 0.5f
internal const val MAX_SPEED = 3.5f

private val presetSpeeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

private val aspectRatioLabels = listOf(
    AspectRatioMode.FIT to "适应屏幕",
    AspectRatioMode.CROP to "裁剪填充",
    AspectRatioMode.STRETCH to "拉伸全屏",
)

internal fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
