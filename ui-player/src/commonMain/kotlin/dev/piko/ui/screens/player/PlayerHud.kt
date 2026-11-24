package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.piko.shared.i18n.tr
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.resources.Res
import dev.piko.ui.resources.player_gesture_episode_no_next
import dev.piko.ui.resources.player_gesture_episode_no_previous
import dev.piko.ui.resources.player_gesture_episode_release_next
import dev.piko.ui.resources.player_gesture_episode_release_previous
import dev.piko.ui.resources.player_gesture_episode_swipe_down
import dev.piko.ui.resources.player_gesture_episode_swipe_up
import dev.piko.ui.resources.player_seek_backward_seconds
import dev.piko.ui.resources.player_seek_forward_seconds
import dev.piko.ui.resources.player_seek_release_to_cancel
import dev.piko.ui.theme.LocalFixedColors
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.tabular
import kotlin.math.abs
import kotlin.math.roundToInt

private val HUD_BAR_WIDTH = 96.dp
private val LevelEdgePillWidth = 32.dp
private val LevelEdgeTrackWidth = 4.dp
private val LevelEdgeTrackHeightCompact = 72.dp
private val LevelEdgeTrackHeightLarge = 104.dp
private val LevelEdgeIconSize = 16.dp
private const val LevelEdgeTrackBgAlpha = 0.24f
private const val SecondaryTextAlpha = 0.7f

/**
 * 顶部居中瞬态开关/提示胶囊（供信息流自动连刷、键盘切换提示等复用）。
 */
@Composable
internal fun PlayerHud(
    indicator: CenterIndicator?,
    showBackground: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val retained = remember { mutableStateOf<CenterIndicator?>(null) }
    if (indicator != null) SideEffect { retained.value = indicator }
    val shown = indicator ?: retained.value

    val fixed = LocalFixedColors.current
    val motion = MaterialTheme.motionScheme
    val reduced = LocalPikoMotion.current.reduced
    val baseStyle = MaterialTheme.typography.labelLarge.tabular
    val style = if (showBackground) baseStyle else baseStyle.copy(shadow = PlayerTextShadow)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val dir = shown?.direction ?: 0

    val enterTransition = if (!reduced && dir != 0) {
        fadeIn(motion.fastEffectsSpec()) + slideInHorizontally(motion.fastSpatialSpec()) { w ->
            (w / 5) * (-dir)
        }
    } else {
        fadeIn(motion.fastEffectsSpec())
    }
    val exitTransition = if (!reduced && dir != 0) {
        fadeOut(motion.defaultEffectsSpec()) + slideOutHorizontally(motion.fastSpatialSpec()) { w ->
            (w / 6) * dir
        }
    } else {
        fadeOut(motion.defaultEffectsSpec())
    }

    AnimatedVisibility(
        visible = indicator != null,
        enter = enterTransition,
        exit = exitTransition,
        modifier = modifier,
    ) {
        val content = shown ?: return@AnimatedVisibility
        val textWidth = with(density) {
            val measured = textMeasurer.measure(content.text, baseStyle).size.width
            val reserved = content.textTemplate?.let { textMeasurer.measure(it, baseStyle).size.width } ?: 0
            maxOf(measured, reserved).toDp()
        }
        Row(
            modifier = Modifier
                .then(
                    if (showBackground) {
                        Modifier
                            .background(fixed.ScrimOnMedia, CircleShape)
                            .border(1.dp, fixed.HudSpecularBorder, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    } else {
                        Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    },
                )
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = content.description
                },
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (content.direction > 0) {
                Text(
                    text = content.text,
                    style = style,
                    color = fixed.OnMedia,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.width(textWidth),
                )
                Icon(content.icon, contentDescription = null, tint = fixed.OnMedia, modifier = Modifier.size(18.dp))
            } else {
                Icon(content.icon, contentDescription = null, tint = fixed.OnMedia, modifier = Modifier.size(18.dp))
                content.progress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        color = fixed.OnMedia,
                        trackColor = fixed.OnMedia.copy(alpha = 0.28f),
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                        modifier = Modifier.width(HUD_BAR_WIDTH),
                    )
                }
                Text(
                    text = content.text,
                    style = style,
                    color = fixed.OnMedia,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.width(textWidth),
                )
            }
        }
    }
}

/**
 * 横划进退中央轻量胶囊指示器：
 * 单行紧凑展示目标时刻、总时长与相对位移秒数（进度由底栏真实进度条同步呈现，避免中央重复叠轨遮挡画面）；
 * 拖入顶部两角取消区后就地切换为「松手取消进退」。
 */
@Composable
internal fun SeekHudOverlay(
    seek: PlayerGesture.Seek?,
    durationMillis: Long,
    showBackground: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val retained = remember { mutableStateOf<PlayerGesture.Seek?>(null) }
    if (seek != null) SideEffect { retained.value = seek }
    val shown = seek ?: retained.value
    val motion = MaterialTheme.motionScheme
    val fixed = LocalFixedColors.current

    AnimatedVisibility(
        visible = seek != null,
        enter = fadeIn(motion.fastEffectsSpec()),
        exit = fadeOut(motion.fastEffectsSpec()),
        modifier = modifier,
    ) {
        val current = shown ?: return@AnimatedVisibility
        val targetMillis = current.targetMillis(durationMillis)
        val deltaMillis = targetMillis - current.startPositionMillis
        val desc = if (current.cancelled) {
            stringResource(Res.string.player_seek_release_to_cancel)
        } else {
            tr(
                "跳到 ${formatTime(targetMillis)}",
                "Seek to ${formatTime(targetMillis)}",
                "${formatTime(targetMillis)} に移動",
                "${formatTime(targetMillis)}(으)로 이동",
                "跳至 ${formatTime(targetMillis)}",
            )
        }
        val primaryTimeStyle = MaterialTheme.typography.titleMedium.tabular.let {
            if (showBackground) it else it.copy(shadow = PlayerTextShadow)
        }
        val secondaryTimeStyle = MaterialTheme.typography.bodySmall.tabular.let {
            if (showBackground) it else it.copy(shadow = PlayerTextShadow)
        }
        val deltaStyle = MaterialTheme.typography.labelMedium.tabular.let {
            if (showBackground) it else it.copy(shadow = PlayerTextShadow)
        }
        val cancelStyle = MaterialTheme.typography.labelLarge.let {
            if (showBackground) it else it.copy(shadow = PlayerTextShadow)
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .then(
                    if (showBackground) {
                        Modifier
                            .clip(CircleShape)
                            .background(fixed.ScrimOnMedia)
                            .border(1.dp, fixed.HudSpecularBorder, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    } else {
                        Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    },
                )
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = desc
                },
        ) {
            if (current.cancelled) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = fixed.OnMedia,
                    modifier = Modifier.size(LevelEdgeIconSize),
                )
                Text(
                    text = stringResource(Res.string.player_seek_release_to_cancel),
                    style = cancelStyle,
                    color = fixed.OnMedia,
                    maxLines = 1,
                )
            } else {
                val seconds = abs(deltaMillis) / 1_000L
                Icon(
                    imageVector = if (deltaMillis >= 0L) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(LevelEdgeIconSize),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTime(targetMillis),
                        style = primaryTimeStyle,
                        color = fixed.OnMedia,
                        maxLines = 1,
                    )
                    Text(
                        text = " / ${formatTime(durationMillis)}",
                        style = secondaryTimeStyle,
                        color = fixed.OnMedia.copy(alpha = SecondaryTextAlpha),
                        maxLines = 1,
                    )
                }
                if (seconds > 0L) {
                    val sign = if (deltaMillis > 0L) "+" else "−"
                    Text(
                        text = "$sign${seconds}s",
                        style = deltaStyle,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 双击与方向键快进/快退贴边胶囊（移植自 Bilby `DoubleTapSeekHint`）：
 * 贴合触发那一侧边缘轻滑进出，与音量/亮度边栏统一视觉语言。
 */
@Composable
internal fun DoubleTapSeekHint(
    visible: Boolean,
    forward: Boolean,
    seconds: Int,
    showBackground: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val retainedForward = remember { mutableStateOf(forward) }
    val retainedSeconds = remember { mutableStateOf(seconds) }
    if (visible) {
        SideEffect {
            retainedForward.value = forward
            retainedSeconds.value = seconds
        }
    }
    val shownForward = if (visible) forward else retainedForward.value
    val shownSeconds = if (visible) seconds else retainedSeconds.value
    val motion = MaterialTheme.motionScheme
    val reduced = LocalPikoMotion.current.reduced
    val spatialOffset = motion.fastSpatialSpec<IntOffset>()
    val effects = motion.fastEffectsSpec<Float>()
    val fixed = LocalFixedColors.current

    AnimatedVisibility(
        visible = visible,
        enter = if (reduced) fadeIn(effects) else slideInHorizontally(spatialOffset) { if (shownForward) it / 2 else -it / 2 } + fadeIn(effects),
        exit = if (reduced) fadeOut(effects) else slideOutHorizontally(spatialOffset) { if (shownForward) it / 2 else -it / 2 } + fadeOut(effects),
        modifier = modifier,
    ) {
        val label = stringResource(
            if (shownForward) Res.string.player_seek_forward_seconds else Res.string.player_seek_backward_seconds,
            shownSeconds,
        )
        val textStyle = MaterialTheme.typography.labelMedium.tabular.let {
            if (showBackground) it else it.copy(shadow = PlayerTextShadow)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .then(
                    if (showBackground) {
                        Modifier
                            .clip(CircleShape)
                            .background(fixed.ScrimOnMedia)
                            .border(1.dp, fixed.HudSpecularBorder, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    } else {
                        Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    },
                )
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = label
                },
        ) {
            Icon(
                imageVector = if (shownForward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(LevelEdgeIconSize),
            )
            Text(
                text = label,
                style = textStyle,
                color = fixed.OnMedia,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * 音量与亮度的极窄贴边竖条指示器（移植自 Bilby `PlayerLevelEdgeBar`）：
 * 触屏纵划时停靠在对侧边缘以避开手指与手掌，滚轮与键盘触发时停靠在右侧边缘。
 */
@Composable
internal fun PlayerLevelEdgeBar(
    adjust: PlayerGesture.Adjust?,
    dockEnd: Boolean,
    large: Boolean,
    showBackground: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val retained = remember { mutableStateOf<PlayerGesture.Adjust?>(null) }
    if (adjust != null) SideEffect { retained.value = adjust }
    val shown = adjust ?: retained.value
    val motion = MaterialTheme.motionScheme
    val reduced = LocalPikoMotion.current.reduced
    val spatialOffset = motion.fastSpatialSpec<IntOffset>()
    val effects = motion.fastEffectsSpec<Float>()
    val fixed = LocalFixedColors.current

    AnimatedVisibility(
        visible = adjust != null,
        enter = if (reduced) fadeIn(effects) else slideInHorizontally(spatialOffset) { if (dockEnd) it / 2 else -it / 2 } + fadeIn(effects),
        exit = if (reduced) fadeOut(effects) else slideOutHorizontally(spatialOffset) { if (dockEnd) it / 2 else -it / 2 } + fadeOut(effects),
        modifier = modifier,
    ) {
        val current = shown ?: return@AnimatedVisibility
        val clamped = current.fraction.coerceIn(0f, 1f)
        val percent = (clamped * 100).roundToInt()
        val isBrightness = current.kind == VerticalAdjust.Brightness
        val icon = when {
            isBrightness && clamped >= 0.5f -> Icons.Filled.BrightnessHigh
            isBrightness -> Icons.Filled.BrightnessLow
            clamped <= 0f -> Icons.AutoMirrored.Filled.VolumeOff
            clamped < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
            else -> Icons.AutoMirrored.Filled.VolumeUp
        }
        val desc = if (isBrightness) {
            tr("亮度 $percent%", "Brightness $percent%", "明るさ $percent%", "밝기 $percent%", "亮度 $percent%")
        } else {
            tr("音量 $percent%", "Volume $percent%", "音量 $percent%", "볼륨 $percent%", "音量 $percent%")
        }
        val trackHeight = if (large) LevelEdgeTrackHeightLarge else LevelEdgeTrackHeightCompact
        val percentStyle = MaterialTheme.typography.labelSmall.tabular.let {
            if (showBackground) it else it.copy(shadow = PlayerTextShadow)
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier
                .width(LevelEdgePillWidth)
                .then(
                    if (showBackground) {
                        Modifier
                            .clip(CircleShape)
                            .background(fixed.ScrimOnMedia)
                            .border(1.dp, fixed.HudSpecularBorder, CircleShape)
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = 5.dp, vertical = 8.dp)
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = desc
                },
        ) {
            Text(
                text = "$percent",
                style = percentStyle,
                color = fixed.OnMedia,
                maxLines = 1,
                softWrap = false,
            )
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .width(LevelEdgeTrackWidth)
                    .height(trackHeight)
                    .clip(CircleShape)
                    .background(fixed.OnMedia.copy(alpha = LevelEdgeTrackBgAlpha)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(clamped)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fixed.OnMedia,
                modifier = Modifier.size(LevelEdgeIconSize),
            )
        }
    }
}

/**
 * 纵划切集手势中央提示胶囊（移植自 Bilby `PlayerShell`）。
 */
@Composable
internal fun EpisodeSwipeHud(
    visible: Boolean,
    direction: Int,
    armed: Boolean,
    available: Boolean,
    showBackground: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val retainedDir = remember { mutableStateOf(direction) }
    val retainedArmed = remember { mutableStateOf(armed) }
    val retainedAvailable = remember { mutableStateOf(available) }
    if (visible && direction != 0) {
        SideEffect {
            retainedDir.value = direction
            retainedArmed.value = armed
            retainedAvailable.value = available
        }
    }
    val shownDir = if (visible && direction != 0) direction else retainedDir.value
    val shownArmed = if (visible && direction != 0) armed else retainedArmed.value
    val shownAvailable = if (visible && direction != 0) available else retainedAvailable.value
    val motion = MaterialTheme.motionScheme
    val fixed = LocalFixedColors.current

    AnimatedVisibility(
        visible = visible && direction != 0,
        enter = fadeIn(motion.fastEffectsSpec()),
        exit = fadeOut(motion.fastEffectsSpec()),
        modifier = modifier,
    ) {
        val isNext = shownDir > 0
        val tint = if (shownArmed) MaterialTheme.colorScheme.primary else fixed.OnMedia
        val labelRes = when {
            isNext && !shownAvailable -> Res.string.player_gesture_episode_no_next
            isNext && shownArmed -> Res.string.player_gesture_episode_release_next
            isNext -> Res.string.player_gesture_episode_swipe_up
            !shownAvailable -> Res.string.player_gesture_episode_no_previous
            shownArmed -> Res.string.player_gesture_episode_release_previous
            else -> Res.string.player_gesture_episode_swipe_down
        }
        val label = stringResource(labelRes)
        val textStyle = MaterialTheme.typography.labelMedium.let {
            if (showBackground) it else it.copy(shadow = PlayerTextShadow)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .then(
                    if (showBackground) {
                        Modifier
                            .clip(CircleShape)
                            .background(fixed.ScrimOnMedia)
                            .border(1.dp, fixed.HudSpecularBorder, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    } else {
                        Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                    },
                )
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = label
                },
        ) {
            Icon(
                imageVector = if (isNext) Icons.Filled.SkipNext else Icons.Filled.SkipPrevious,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = textStyle,
                color = tint,
                maxLines = 1,
            )
        }
    }
}
