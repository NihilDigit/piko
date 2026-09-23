package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nihildigit.pikpak.FileStat

/**
 * 手势 HUD：竖滑显示亮度或音量，横滑显示目标时间与偏移量。
 *
 * 这是手势唯一的视觉反馈，所以标成 liveRegion，读屏用户拖动时也能听到数值。
 */
@Composable
internal fun PlayerGestureHud(
    gesture: PlayerGesture,
    durationMillis: Long,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = HUD_CONTAINER_ALPHA),
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            when (gesture) {
                is PlayerGesture.Adjust -> AdjustHudContent(gesture)
                is PlayerGesture.Seek -> SeekHudContent(gesture, durationMillis)
            }
        }
    }
}

@Composable
private fun AdjustHudContent(gesture: PlayerGesture.Adjust) {
    Row(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = when {
                gesture.kind == VerticalAdjust.Brightness -> Icons.Filled.BrightnessMedium
                gesture.fraction <= 0f -> Icons.AutoMirrored.Filled.VolumeMute
                else -> Icons.AutoMirrored.Filled.VolumeUp
            },
            contentDescription = if (gesture.kind == VerticalAdjust.Brightness) "亮度" else "音量",
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(16.dp))
        LinearProgressIndicator(
            progress = { gesture.fraction },
            modifier = Modifier.width(140.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = "${(gesture.fraction * 100).toInt()}",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp),
        )
    }
}

@Composable
private fun SeekHudContent(gesture: PlayerGesture.Seek, durationMillis: Long) {
    val target = gesture.targetMillis(durationMillis)
    val deltaSeconds = (target - gesture.startPositionMillis) / 1000
    Column(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = formatTime(target),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = " / ${formatTime(durationMillis)}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (deltaSeconds >= 0) "+$deltaSeconds 秒" else "$deltaSeconds 秒",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        if (durationMillis > 0) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { target.toFloat() / durationMillis },
                modifier = Modifier.width(200.dp),
            )
        }
    }
}

/**
 * 双击快进快退的反馈：落点一侧的半圆弧形区域，显示本轮累计的秒数。
 *
 * 连续双击同一侧会累加，所以显示的是累计值而不是固定的 10 秒；弧形贴着屏幕边缘，
 * 说明操作属于这一侧，同时不遮住画面中央。
 */
@Composable
internal fun DoubleTapIndicator(
    forward: Boolean,
    seconds: Int,
    modifier: Modifier = Modifier,
) {
    val arc = if (forward) {
        RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
    } else {
        RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
    }
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(if (forward) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(SIDE_ZONE_FRACTION)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = DOUBLE_TAP_ARC_ALPHA), arc)
                .semantics { liveRegion = LiveRegionMode.Polite },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = if (forward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (forward) "+$seconds 秒" else "-$seconds 秒",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/**
 * 长按倍速播放提示，贴顶部居中。
 */
@Composable
internal fun BoxScope.SpeedBoostCapsule(
    visible: Boolean,
    isLandscape: Boolean,
    speed: Float,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.fastEffectsSpec()) + slideInVertically(motion.fastSpatialSpec()) { -it },
        exit = fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastSpatialSpec()) { -it },
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
            .padding(top = if (isLandscape) 16.dp else 64.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = HUD_CONTAINER_ALPHA),
            contentColor = MaterialTheme.colorScheme.onSurface,
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
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${formatSpeed(speed)} 播放中",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * 竖屏放横屏片子时的全屏入口，落在画面下方的黑边里。
 *
 * 全屏入口在底栏那排图标里太小也太远，这里给一个落在拇指位置的 Medium 按钮。
 * 位置随控件栏让路：控件栏出来时抬到它上面，收起后回到贴近画面的位置。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun BoxScope.FullscreenPromptButton(
    visible: Boolean,
    controlsVisible: Boolean,
    onClick: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it / 2 },
        exit = fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastSpatialSpec()) { it / 2 },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
            .padding(bottom = if (controlsVisible) 132.dp else 48.dp),
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.height(ButtonDefaults.MediumContainerHeight),
            contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
        ) {
            Icon(
                imageVector = Icons.Filled.Fullscreen,
                contentDescription = null,
                modifier = Modifier.size(ButtonDefaults.MediumIconSize),
            )
            Spacer(Modifier.width(8.dp))
            Text("全屏播放", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * 续播提示：「从 xx 继续播放」，附带「从头播放」。
 *
 * 形式上就是一条带操作的 Snackbar：短暂、不打断播放、只有一个操作，
 * 用 Snackbar 组件本身而不是自己拼一个胶囊。
 */
@Composable
internal fun BoxScope.ResumeTipCapsule(
    visible: Boolean,
    resumedPositionMillis: Long,
    isLandscape: Boolean,
    controlsVisible: Boolean,
    onRestart: () -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motion.defaultEffectsSpec()) + slideInVertically(motion.defaultSpatialSpec()) { it },
        exit = fadeOut(motion.fastEffectsSpec()) + slideOutVertically(motion.fastSpatialSpec()) { it },
        modifier = Modifier
            .align(Alignment.BottomStart)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            )
            .padding(
                start = 16.dp,
                end = 16.dp,
                bottom = when {
                    controlsVisible && isLandscape -> 104.dp
                    controlsVisible -> 120.dp
                    else -> 16.dp
                },
            ),
    ) {
        Snackbar(
            action = {
                TextButton(onClick = onRestart) { Text("从头播放") }
            },
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            Text("从 ${formatTime(resumedPositionMillis)} 继续播放")
        }
    }
}

/**
 * 同目录视频列表。播放中换片不必退回网盘列表再进来一次。
 *
 * 当前这条用 primaryContainer 标出来，并在打开时滚到它那里：一个目录里几十集的
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
    ) {
        Text(
            text = "同目录视频",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(videos, key = { _, item -> item.id }) { index, video ->
                val isCurrent = video.id == currentFileId
                val contentColor = if (isCurrent) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                Surface(
                    onClick = { onSelect(video) },
                    shape = MaterialTheme.shapes.large,
                    color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                    contentColor = contentColor,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isCurrent) contentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(36.dp),
                        )
                        Text(
                            text = video.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "正在播放",
                                modifier = Modifier.padding(start = 8.dp).size(20.dp),
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
 *
 * 用可切换图标按钮：锁定态换成方角并填充，和未锁定一眼能分开。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LockToggle(
    isLocked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalIconToggleButton(
        checked = isLocked,
        onCheckedChange = { onToggle() },
        shapes = IconButtonDefaults.toggleableShapes(),
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (isLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
            contentDescription = if (isLocked) "解锁屏幕" else "锁定屏幕",
        )
    }
}

/**
 * 加载指示。控件栏收起时单独显示在画面中央；控件栏展开时由播放键原位替代，不重复显示。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlayerLoadingIndicator(modifier: Modifier = Modifier) {
    LoadingIndicator(
        modifier = modifier
            .size(LOADING_INDICATOR_SIZE)
            .semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * 播放失败提示：原因、重试，可选返回。
 *
 * 错误卡片出现时控件栏保持展开，返回键始终可达；卡片里的返回是给竖屏单手操作的近路。
 */
@Composable
internal fun PlaybackErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .padding(24.dp)
            .widthIn(max = 400.dp)
            .semantics { liveRegion = LiveRegionMode.Assertive },
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("无法播放", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onBack != null) {
                    OutlinedButton(onClick = onBack) { Text("返回") }
                }
                Button(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

private const val HUD_CONTAINER_ALPHA = 0.9f
private const val DOUBLE_TAP_ARC_ALPHA = 0.16f
private val LOADING_INDICATOR_SIZE = 64.dp
