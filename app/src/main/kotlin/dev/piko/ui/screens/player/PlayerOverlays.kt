package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.piko.ui.theme.LocalFixedColors
import io.github.nihildigit.pikpak.FileStat

/**
 * 手势 HUD：竖滑显示亮度/音量，横滑显示目标时间与偏移量。
 */
@Composable
internal fun PlayerGestureHud(
    gesture: PlayerGesture,
    durationMillis: Long,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            color = Color.Black.copy(alpha = 0.75f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
            modifier = Modifier.padding(16.dp),
        ) {
            when (gesture) {
                is PlayerGesture.Adjust -> {
                    Row(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = when {
                                gesture.kind == VerticalAdjust.Brightness -> Icons.Filled.BrightnessMedium
                                gesture.fraction <= 0.02f -> Icons.AutoMirrored.Filled.VolumeMute
                                else -> Icons.AutoMirrored.Filled.VolumeUp
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.width(130.dp)) {
                            Text(
                                text = "${(gesture.fraction * 100).toInt()}%",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { gesture.fraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(CircleShape),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.White.copy(alpha = 0.25f),
                            )
                        }
                    }
                }

                is PlayerGesture.Seek -> {
                    val target = (gesture.startPositionMillis + gesture.deltaMillis)
                        .coerceIn(0L, durationMillis.coerceAtLeast(1L))
                    val deltaSeconds = (gesture.deltaMillis / 1000).toInt()
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "${formatTime(target)} / ${formatTime(durationMillis)}",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = if (deltaSeconds >= 0) "[+$deltaSeconds 秒]" else "[$deltaSeconds 秒]",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (deltaSeconds >= 0) {
                                LocalFixedColors.current.InstantMatchGreen
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 双击快进/快退的方向指示。
 */
@Composable
internal fun DoubleTapIndicator(
    forward: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 64.dp),
        contentAlignment = if (forward) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.65f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (forward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (forward) "+10 秒" else "-10 秒",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/**
 * 长按倍速播放提示。
 */
@Composable
internal fun BoxScope.SpeedBoostCapsule(
    visible: Boolean,
    isLandscape: Boolean,
    speed: Float,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = if (isLandscape) 24.dp else 52.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.75f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "${speed}x 加速播放中",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * 竖屏放横屏片子时的全屏入口，落在画面下方的黑边里。
 *
 * 位置随控件栏让路：控件栏出来时抬到它上面，收起后回到贴近画面的位置，
 * 两种情况下都不会盖住进度条。
 */
@Composable
internal fun BoxScope.FullscreenPromptButton(
    visible: Boolean,
    controlsVisible: Boolean,
    onClick: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = if (controlsVisible) 132.dp else 48.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Fullscreen,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "全屏播放",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/**
 * 续播提示，附带「从头播放」。
 */
@Composable
internal fun BoxScope.ResumeTipCapsule(
    visible: Boolean,
    resumedPositionMillis: Long,
    isLandscape: Boolean,
    controlsVisible: Boolean,
    onRestart: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(
                start = if (isLandscape) 40.dp else 20.dp,
                bottom = when {
                    controlsVisible && isLandscape -> 100.dp
                    controlsVisible -> 120.dp
                    isLandscape -> 28.dp
                    else -> 36.dp
                },
            ),
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.8f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "已恢复至 ${formatTime(resumedPositionMillis)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "从头播放",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onRestart),
                )
            }
        }
    }
}

/**
 * 同目录视频列表。播放中换片不必退回网盘列表再进来一次。
 *
 * 当前这条用 primaryContainer 标出来，并在打开时滚到它那里——一个目录里几十集的
 * 情况很常见，落在顶部等于每次都要自己翻。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerPlaylistSheet(
    videos: List<FileStat>,
    currentFileId: String,
    onSelect: (FileStat) -> Unit,
    onDismiss: () -> Unit,
) {
    val currentIndex = videos.indexOfFirst { it.id == currentFileId }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = MaterialTheme.shapes.large,
    ) {
        Text(
            text = "同目录视频 · ${videos.size}",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            itemsIndexed(videos, key = { _, item -> item.id }) { index, video ->
                val isCurrent = video.id == currentFileId
                Surface(
                    onClick = { onSelect(video) },
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        Color.Transparent
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.width(32.dp),
                        )
                        Text(
                            text = video.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isCurrent) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "正在播放",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 锁定手势与控件的开关。锁定后它是唯一可点的控件。
 */
@Composable
internal fun LockToggle(
    isLocked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onToggle,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.6f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
        modifier = modifier.size(48.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = if (isLocked) "解锁" else "锁定",
                tint = if (isLocked) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/**
 * 播放失败提示与重试。
 */
@Composable
internal fun PlaybackErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(24.dp),
        shape = MaterialTheme.shapes.large,
        color = Color.Black.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("播放中断", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.72f),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}
