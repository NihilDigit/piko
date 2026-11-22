package dev.piko.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.piko.shared.media.player.CenterSlotAction
import dev.piko.shared.media.player.CenterSlotConfig
import dev.piko.shared.media.player.CenterSlotPosition
import dev.piko.shared.media.player.ListenCoverStyle
import dev.piko.shared.media.player.ListenLayoutConfig
import dev.piko.shared.media.player.ListenQuickChip
import dev.piko.shared.media.player.PlayerScreenState
import dev.piko.shared.media.player.PlaylistEntry
import dev.piko.ui.components.InlineLoadingIndicator
import dev.piko.ui.components.PikoDialog
import dev.piko.ui.components.PikoSheet
import dev.piko.ui.components.TooltipIconButton
import dev.piko.ui.components.formatTimeMs
import dev.piko.ui.i18n.stringResource
import dev.piko.ui.platform.captionExclusion
import dev.piko.ui.platform.windowDragArea
import dev.piko.ui.resources.*
import dev.piko.ui.theme.LocalAppearance
import dev.piko.ui.theme.LocalPikoMotion
import dev.piko.ui.theme.LocalWindowVisible
import dev.piko.ui.theme.isEInk
import dev.piko.ui.theme.tabular
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot

private const val DiscSizeFraction = 0.84f
private const val DiscLabelFraction = 0.58f
private const val DiscHoleFraction = 0.055f
private const val DiscGrooveCount = 4
private const val DiscGrooveWidthPx = 1.2f
private const val DiscRotationPeriodMillis = 36_000
private const val DiscFrameDelayMillis = 33L
private const val CancelRadiusRatio = 1.5f

private val SpeedPresets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
private val SleepTimerMinutePresets = listOf(15, 30, 45, 60)

private sealed interface ListenSleepTimer {
    data object Off : ListenSleepTimer
    data object AfterCurrent : ListenSleepTimer
    data class Countdown(val remainingSeconds: Int) : ListenSleepTimer
}

@Stable
private class ListenScrubState {
    var dragPositionMillis by mutableStateOf<Long?>(null)
    var cancelArmed by mutableStateOf(false)
    val isScrubbing: Boolean get() = dragPositionMillis != null
}

@Immutable
internal data class ListenQuickActionItem(
    val icon: ImageVector,
    val label: String,
    val highlighted: Boolean,
    val onClick: () -> Unit,
)

internal data class ListenEditHooks(
    val selectedSlot: CenterSlotPosition,
    val dropTargetSlot: CenterSlotPosition?,
    val onSelectSlot: (CenterSlotPosition) -> Unit,
    val onSlotBounds: (CenterSlotPosition, Rect) -> Unit,
)

/**
 * 音频与「听视频」播放页（移植自 Bilby ListenScreen 并适配 Piko 播放状态与 M3 Expressive 规范）：
 * - 30fps 节流黑胶唱片旋转（带启动/停止物理惯性），受 [LocalWindowVisible] 与 [LocalPikoMotion.reduced] 门控；
 * - 支持直接转动唱片拖动进度与离盘取消区、顶部/进度条等宽 `.tabular` 读数；
 * - 支持倍速、定时关闭（倒计时 / 播完当前停止）、音量调节、文件夹队列（宽屏右侧常驻双栏，窄屏底部/侧边 Sheet）；
 * - 纯音频文件打开时自动以此页面呈现；从视频切入时传入 [onSwitchToVideo] 供一键返回画面。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ListenScreen(
    state: PlayerScreenState,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    volumeControl: PlayerLevelControl? = null,
    windowActions: (@Composable RowScope.() -> Unit)? = null,
    onSwitchToVideo: (() -> Unit)? = null,
    topChromePadding: Dp = 0.dp,
    showBackButton: Boolean = true,
    registerKeyFallback: (((KeyEvent) -> Boolean)?) -> Unit = {},
) {
    val layoutPrefs = LocalPlayerLayoutPrefs.current
    val listenLayout = layoutPrefs.listen
    val scrub = remember { ListenScrubState() }
    var resumeAfterDrag by remember { mutableStateOf(false) }
    var speedDialogOpen by remember { mutableStateOf(false) }
    var sleepDialogOpen by remember { mutableStateOf(false) }
    var queueSheetOpen by remember { mutableStateOf(false) }
    var sleepTimer by remember { mutableStateOf<ListenSleepTimer>(ListenSleepTimer.Off) }
    var lastFileIdForSleep by remember { mutableStateOf(state.fileId) }
    var volumeValue by remember(volumeControl) { mutableFloatStateOf(volumeControl?.current() ?: 1f) }
    var volumeBeforeMute by remember { mutableFloatStateOf(0.5f) }
    var containerWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }

    // 定时关闭：倒计时每秒递减；「播完当前停止」在曲目切换瞬间暂停
    LaunchedEffect(sleepTimer) {
        val current = sleepTimer
        if (current is ListenSleepTimer.Countdown) {
            if (current.remainingSeconds <= 0) {
                state.pause()
                sleepTimer = ListenSleepTimer.Off
            } else {
                delay(1_000L)
                sleepTimer = ListenSleepTimer.Countdown(current.remainingSeconds - 1)
            }
        }
    }
    LaunchedEffect(state.fileId) {
        if (state.fileId != lastFileIdForSleep) {
            lastFileIdForSleep = state.fileId
            if (sleepTimer is ListenSleepTimer.AfterCurrent) {
                state.pause()
                sleepTimer = ListenSleepTimer.Off
            }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val seekBySeconds: (Int) -> Unit = { deltaSeconds ->
        state.seekBy(deltaSeconds * 1_000L)
    }

    val latestState by rememberUpdatedState(state)
    val latestBack by rememberUpdatedState(onBackClick)
    val latestVolume by rememberUpdatedState(volumeControl)
    val handleKeyEvent: (KeyEvent) -> Boolean = remember {
        { event ->
            if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) {
                false
            } else {
                val cur = latestState
                val dur = cur.durationMillis
                val digit = listenDigitKey(event.key)
                if (digit != null && !event.isShiftPressed && dur > 0L) {
                    cur.seekTo((dur * digit / 10L).coerceIn(0L, dur))
                    true
                } else {
                    when (event.key) {
                        Key.Spacebar -> {
                            cur.togglePlayPause()
                            true
                        }
                        Key.DirectionLeft, Key.DirectionRight -> {
                            val step = if (event.isShiftPressed) 60_000L else 10_000L
                            cur.seekBy(if (event.key == Key.DirectionLeft) -step else step)
                            true
                        }
                        Key.DirectionUp -> {
                            val vc = latestVolume ?: return@remember false
                            volumeValue = vc.set((vc.current() + 0.05f).coerceIn(0f, 1f))
                            true
                        }
                        Key.DirectionDown -> {
                            val vc = latestVolume ?: return@remember false
                            volumeValue = vc.set((vc.current() - 0.05f).coerceIn(0f, 1f))
                            true
                        }
                        Key.M -> {
                            val vc = latestVolume ?: return@remember false
                            val v = vc.current()
                            if (v > 0f) {
                                volumeBeforeMute = v
                                volumeValue = vc.set(0f)
                            } else {
                                volumeValue = vc.set(volumeBeforeMute.coerceAtLeast(0.2f))
                            }
                            true
                        }
                        Key.LeftBracket -> {
                            if (cur.previousEntry != null) {
                                cur.playPrevious()
                                true
                            } else false
                        }
                        Key.RightBracket -> {
                            if (cur.nextEntry != null) {
                                cur.playNext()
                                true
                            } else false
                        }
                        Key.Escape -> {
                            latestBack()
                            true
                        }
                        else -> false
                    }
                }
            }
        }
    }

    DisposableEffect(registerKeyFallback, handleKeyEvent) {
        registerKeyFallback(handleKeyEvent)
        onDispose { registerKeyFallback(null) }
    }

    val isWideWithQueue = with(density) { containerWidthPx.toDp() } >= 820.dp && state.playlist.size > 1
    val coverUrl = state.currentEntry?.thumbnailUrl.orEmpty()

    Surface(
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerWidthPx = it.width }
            .onKeyEvent(handleKeyEvent)
            .focusRequester(focusRequester)
            .focusable(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            val listenModalOpen = speedDialogOpen || sleepDialogOpen || queueSheetOpen
            // 顶栏（极简：返回 + 可拖拽空白 + 可选切回视频 + 音量 + 窗口控制按钮）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 6.dp + topChromePadding, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (showBackButton) {
                    TooltipIconButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        label = stringResource(Res.string.player_custom_ctrl_back),
                        onClick = onBackClick,
                        modifier = Modifier.captionExclusion(),
                    )
                }
                Spacer(Modifier.weight(1f).height(40.dp).windowDragArea(enabled = !listenModalOpen))
                if (onSwitchToVideo != null) {
                    FilledTonalButton(
                        onClick = onSwitchToVideo,
                        shapes = ButtonDefaults.shapes(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.captionExclusion().handCursor(),
                    ) {
                        Icon(Icons.Outlined.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(Res.string.listen_switch_to_video), maxLines = 1)
                    }
                }
                if (volumeControl != null) {
                    Box(Modifier.captionExclusion()) {
                        ListenVolumeControl(
                            volume = volumeValue,
                            onVolumeChange = { next ->
                                volumeValue = volumeControl.set(next)
                                if (volumeValue > 0f) volumeBeforeMute = volumeValue
                            },
                            onToggleMute = {
                                if (volumeValue > 0f) {
                                    volumeBeforeMute = volumeValue
                                    volumeValue = volumeControl.set(0f)
                                } else {
                                    volumeValue = volumeControl.set(volumeBeforeMute.coerceAtLeast(0.2f))
                                }
                            },
                        )
                    }
                }
                if (windowActions != null) {
                    Row(
                        modifier = Modifier.captionExclusion(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        windowActions()
                    }
                }
            }

            // 主内容区：宽屏双栏（左主聆听区 + 右侧常驻队列），窄屏单栏
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
            ) {
                ListenMainPane(
                    state = state,
                    coverUrl = coverUrl,
                    listenLayout = listenLayout,
                    scrub = scrub,
                    sleepTimer = sleepTimer,
                    showQueueChip = !isWideWithQueue && state.playlist.size > 1 && listenLayout.hasChip(ListenQuickChip.Queue),
                    onScrubStart = {
                        resumeAfterDrag = state.isPlaying
                        state.pause()
                    },
                    onScrub = { target -> scrub.dragPositionMillis = target },
                    onScrubEnd = {
                        scrub.dragPositionMillis?.let(state::seekTo)
                        scrub.dragPositionMillis = null
                        scrub.cancelArmed = false
                        if (resumeAfterDrag) state.play()
                    },
                    onScrubCancel = {
                        scrub.dragPositionMillis = null
                        scrub.cancelArmed = false
                        if (resumeAfterDrag) state.play()
                    },
                    onSeekBySeconds = seekBySeconds,
                    onOpenSpeed = { speedDialogOpen = true },
                    onOpenSleep = { sleepDialogOpen = true },
                    onOpenQueue = { queueSheetOpen = true },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )

                if (isWideWithQueue) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = RoundedCornerShape(topStart = 24.dp),
                        modifier = Modifier.width(340.dp).fillMaxHeight(),
                    ) {
                        ListenQueueColumn(
                            entries = state.playlist,
                            currentFileId = state.fileId,
                            isPlaying = state.isPlaying,
                            onSelect = state::playEntry,
                        )
                    }
                }
            }
        }
    }

    // 倍速选择对话框
    if (speedDialogOpen) {
        val activeSpeed = state.playbackSpeed ?: 1f
        PikoDialog(
            onDismissRequest = { speedDialogOpen = false },
            icon = { Icon(Icons.Filled.Speed, contentDescription = null) },
            title = { Text(stringResource(Res.string.listen_speed)) },
            text = {
                Column(modifier = Modifier.selectableGroup()) {
                    SpeedPresets.forEach { preset ->
                        val selected = abs(activeSpeed - preset) < 0.01f
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .selectable(selected = selected, role = Role.RadioButton) {
                                    state.setSpeed(preset)
                                    speedDialogOpen = false
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = formatListenSpeed(preset),
                                style = MaterialTheme.typography.bodyLarge.tabular,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { speedDialogOpen = false }) {
                    Text(stringResource(Res.string.common_close))
                }
            },
        )
    }

    // 定时关闭对话框
    if (sleepDialogOpen) {
        PikoDialog(
            onDismissRequest = { sleepDialogOpen = false },
            icon = { Icon(Icons.Filled.Bedtime, contentDescription = null) },
            title = { Text(stringResource(Res.string.listen_sleep_timer)) },
            text = {
                Column(modifier = Modifier.selectableGroup()) {
                    ListenSleepOptionRow(
                        label = stringResource(Res.string.listen_sleep_off),
                        selected = sleepTimer is ListenSleepTimer.Off,
                        onClick = {
                            sleepTimer = ListenSleepTimer.Off
                            sleepDialogOpen = false
                        },
                    )
                    ListenSleepOptionRow(
                        label = stringResource(Res.string.listen_sleep_after_current),
                        selected = sleepTimer is ListenSleepTimer.AfterCurrent,
                        onClick = {
                            sleepTimer = ListenSleepTimer.AfterCurrent
                            sleepDialogOpen = false
                        },
                    )
                    SleepTimerMinutePresets.forEach { minutes ->
                        val seconds = minutes * 60
                        val selected = (sleepTimer as? ListenSleepTimer.Countdown)?.remainingSeconds in (seconds - 59)..seconds
                        ListenSleepOptionRow(
                            label = stringResource(Res.string.listen_sleep_minutes_fmt, minutes),
                            selected = selected,
                            onClick = {
                                sleepTimer = ListenSleepTimer.Countdown(seconds)
                                sleepDialogOpen = false
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { sleepDialogOpen = false }) {
                    Text(stringResource(Res.string.common_close))
                }
            },
        )
    }

    // 窄屏/移动端队列面板
    if (queueSheetOpen && !isWideWithQueue) {
        PikoSheet(
            onDismissRequest = { queueSheetOpen = false },
            sideSheetTitle = stringResource(Res.string.listen_queue_count_fmt, state.playlist.size),
        ) {
            ListenQueueColumn(
                entries = state.playlist,
                currentFileId = state.fileId,
                isPlaying = state.isPlaying,
                onSelect = { entry ->
                    state.playEntry(entry)
                    queueSheetOpen = false
                },
                modifier = Modifier.fillMaxWidth().height(420.dp),
            )
        }
    }
}

@Composable
private fun ListenMainPane(
    state: PlayerScreenState,
    coverUrl: String,
    listenLayout: ListenLayoutConfig,
    scrub: ListenScrubState,
    sleepTimer: ListenSleepTimer,
    showQueueChip: Boolean,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    onScrubCancel: () -> Unit,
    onSeekBySeconds: (Int) -> Unit,
    onOpenSpeed: () -> Unit,
    onOpenSleep: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val speedLabelText = stringResource(Res.string.listen_speed)
    val sleepOffLabel = stringResource(Res.string.listen_sleep_timer)
    val sleepAfterLabel = stringResource(Res.string.listen_sleep_after_current)
    val sleepLabel = when (sleepTimer) {
        ListenSleepTimer.Off -> sleepOffLabel
        ListenSleepTimer.AfterCurrent -> sleepAfterLabel
        is ListenSleepTimer.Countdown -> stringResource(
            Res.string.listen_sleep_remaining_fmt,
            formatCountdown(sleepTimer.remainingSeconds),
        )
    }
    val queueLabel = if (showQueueChip) {
        stringResource(Res.string.listen_queue_count_fmt, state.playlist.size)
    } else {
        ""
    }
    val quickActions = buildList {
        if (listenLayout.hasChip(ListenQuickChip.Speed) && state.playbackSpeed != null) {
            val activeSpeed = state.playbackSpeed ?: 1f
            add(
                ListenQuickActionItem(
                    icon = Icons.Filled.Speed,
                    label = if (abs(activeSpeed - 1f) < 0.01f) speedLabelText else formatListenSpeed(activeSpeed),
                    highlighted = abs(activeSpeed - 1f) >= 0.01f,
                    onClick = onOpenSpeed,
                ),
            )
        }
        if (listenLayout.hasChip(ListenQuickChip.SleepTimer)) {
            add(
                ListenQuickActionItem(
                    icon = Icons.Filled.Bedtime,
                    label = sleepLabel,
                    highlighted = sleepTimer !is ListenSleepTimer.Off,
                    onClick = onOpenSleep,
                ),
            )
        }
        if (showQueueChip) {
            add(
                ListenQuickActionItem(
                    icon = Icons.AutoMirrored.Outlined.QueueMusic,
                    label = queueLabel,
                    highlighted = false,
                    onClick = onOpenQueue,
                ),
            )
        }
    }

    ListenMainContent(
        title = state.title.substringBeforeLast('.').ifBlank { state.title },
        coverUrl = coverUrl,
        listenLayout = listenLayout,
        isPlaying = state.isPlaying,
        isLoading = state.isLoading,
        loadingSpeedText = state.loadingSpeedText,
        errorMessage = state.errorMessage,
        onRetry = state::retry,
        positionMillisProvider = { state.positionMillis },
        readoutPositionMillis = scrub.dragPositionMillis ?: state.positionMillis,
        durationMillis = state.durationMillis,
        bufferedPositionMillisProvider = { state.bufferedPositionMillis },
        playbackSpeed = state.playbackSpeed ?: 1f,
        isScrubbing = scrub.isScrubbing,
        cancelArmed = scrub.cancelArmed,
        hasPrevious = state.previousEntry != null,
        hasNext = state.nextEntry != null,
        onTogglePlayPause = state::togglePlayPause,
        onPrevious = state::playPrevious,
        onNext = state::playNext,
        onSeek = state::seekTo,
        onSeekBarScrub = { preview -> scrub.dragPositionMillis = preview },
        previewSeekMillis = scrub.dragPositionMillis,
        onSeekBySeconds = onSeekBySeconds,
        onDiscScrubStart = onScrubStart,
        onDiscScrub = onScrub,
        onDiscScrubEnd = onScrubEnd,
        onDiscCancelArmedChange = { scrub.cancelArmed = it },
        onDiscScrubCancel = onScrubCancel,
        quickActions = quickActions,
        compact = false,
        editHooks = null,
        modifier = modifier,
    )
}

/**
 * 听音频主舞台单一权威实现：同时驱动真实 [ListenMainPane] 与自定义页 `ListenPreviewCanvas`，
 * 确保封面/黑胶唱片、标题、时间读数、波浪进度条、5 槽位控制行与底部胶囊行 100% 零分叉。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ListenMainContent(
    title: String,
    coverUrl: String,
    listenLayout: ListenLayoutConfig,
    isPlaying: Boolean,
    positionMillisProvider: () -> Long,
    readoutPositionMillis: Long,
    durationMillis: Long,
    bufferedPositionMillisProvider: () -> Long,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    loadingSpeedText: String? = null,
    errorMessage: String? = null,
    onRetry: () -> Unit = {},
    playbackSpeed: Float = 1f,
    isScrubbing: Boolean = false,
    cancelArmed: Boolean = false,
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onSeek: (Long) -> Unit = {},
    onSeekBarScrub: (Long?) -> Unit = {},
    previewSeekMillis: Long? = null,
    onSeekBySeconds: (Int) -> Unit = {},
    onDiscScrubStart: () -> Unit = {},
    onDiscScrub: (Long) -> Unit = {},
    onDiscScrubEnd: () -> Unit = {},
    onDiscCancelArmedChange: (Boolean) -> Unit = {},
    onDiscScrubCancel: () -> Unit = {},
    quickActions: List<ListenQuickActionItem> = emptyList(),
    compact: Boolean = false,
    editHooks: ListenEditHooks? = null,
) {
    val isPreview = editHooks != null
    val density = LocalDensity.current
    var artWidthDp by remember { mutableStateOf(0.dp) }
    var artHeightDp by remember { mutableStateOf(0.dp) }
    val previewCoverSize = if (compact) 84.dp else 144.dp

    Column(
        modifier = modifier
            .then(
                if (isPreview) {
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 18.dp)
                } else {
                    Modifier
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 20.dp)
                },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 封面 / 黑胶唱片区（真实页面自适应占用剩余高度，自定义页在可滚动容器内使用确定尺寸）
        Box(
            modifier = if (isPreview) {
                Modifier.size(previewCoverSize)
            } else {
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        artWidthDp = with(density) { size.width.toDp() }
                        artHeightDp = with(density) { size.height.toDp() }
                    }
            },
            contentAlignment = Alignment.Center,
        ) {
            if (listenLayout.coverStyle == ListenCoverStyle.StaticCover) {
                ListenStaticCover(
                    coverUrl = coverUrl,
                    maxWidth = if (isPreview) previewCoverSize else artWidthDp,
                    maxHeight = if (isPreview) previewCoverSize else artHeightDp,
                    minSide = if (isPreview) previewCoverSize else 140.dp,
                )
            } else if (isPreview) {
                ListenVinylDiscVisual(
                    coverUrl = coverUrl,
                    discSize = previewCoverSize,
                )
            } else {
                val discSize = (minOf(artWidthDp, artHeightDp) * DiscSizeFraction).coerceIn(140.dp, 320.dp)
                ListenVinylDisc(
                    coverUrl = coverUrl,
                    discSize = discSize,
                    isPlaying = isPlaying && !isLoading,
                    speed = playbackSpeed,
                    positionMillisProvider = { previewSeekMillis ?: positionMillisProvider() },
                    durationMillis = durationMillis,
                    isScrubbing = isScrubbing,
                    cancelArmed = cancelArmed,
                    onScrubStart = onDiscScrubStart,
                    onScrub = onDiscScrub,
                    onScrubEnd = onDiscScrubEnd,
                    onCancelArmedChange = onDiscCancelArmedChange,
                    onScrubCancel = onDiscScrubCancel,
                )
            }

            if (!isPreview) {
                val motion = MaterialTheme.motionScheme
                androidx.compose.animation.AnimatedVisibility(
                    visible = cancelArmed,
                    enter = fadeIn(motion.fastEffectsSpec()) + scaleIn(motion.fastSpatialSpec(), initialScale = 0.85f),
                    exit = fadeOut(motion.fastEffectsSpec()) + scaleOut(motion.fastSpatialSpec(), targetScale = 0.85f),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = CircleShape,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(
                                text = stringResource(Res.string.listen_release_to_cancel),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }

        if (isPreview) {
            Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
        }

        // 错误或加载提示
        if (errorMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TextButton(onClick = onRetry) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(Res.string.listen_retry))
                    }
                }
            }
        } else if (isLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                InlineLoadingIndicator()
                if (!loadingSpeedText.isNullOrBlank()) {
                    Text(
                        text = loadingSpeedText,
                        style = MaterialTheme.typography.labelMedium.tabular,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // 标题与来源小标（避免重复信息：标题一行/两行居中）
        Text(
            text = title,
            style = if (compact) MaterialTheme.typography.titleMediumEmphasized else MaterialTheme.typography.headlineSmallEmphasized,
            textAlign = TextAlign.Center,
            maxLines = if (isPreview) 1 else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().widthIn(max = if (isPreview) 360.dp else 520.dp),
        )
        Spacer(Modifier.height(6.dp))

        if (!compact) {
            // 等宽时间大读数（按住拖动染主题色，进入取消区退为次要色）
            ListenTimeReadout(
                positionMillis = readoutPositionMillis,
                durationMillis = durationMillis,
                active = isScrubbing,
                cancelArmed = cancelArmed,
            )
            Spacer(Modifier.height(8.dp))
        }

        // 细轨道进度条（复用 Piko PlayerSeekBar，仅音频播放启用 M3E 波浪轨道）
        Box(modifier = Modifier.fillMaxWidth().widthIn(max = if (isPreview) 360.dp else 520.dp)) {
            PlayerSeekBar(
                positionMillisProvider = positionMillisProvider,
                durationMillis = durationMillis,
                bufferedPositionMillisProvider = bufferedPositionMillisProvider,
                onSeek = onSeek,
                isPlaying = isPlaying,
                playbackSpeed = playbackSpeed,
                wavy = true,
                onScrub = onSeekBarScrub,
                previewMillis = previewSeekMillis,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))

        // 5 槽位播放控制行（遵循 LocalPlayerLayoutPrefs.current.listen 配置）
        ListenTransportRow(
            config = listenLayout,
            isPlaying = isPlaying,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onTogglePlayPause = onTogglePlayPause,
            onPrevious = onPrevious,
            onNext = onNext,
            onSeekBySeconds = onSeekBySeconds,
            previewMode = isPreview,
            selectedSlot = editHooks?.selectedSlot,
            onSelectSlot = editHooks?.onSelectSlot,
            dropTargetSlot = editHooks?.dropTargetSlot,
            onSlotBounds = editHooks?.onSlotBounds,
        )

        if (!compact && quickActions.isNotEmpty()) {
            Spacer(Modifier.height(if (isPreview) 10.dp else 16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                quickActions.forEach { action ->
                    ListenActionPill(
                        icon = action.icon,
                        label = action.label,
                        highlighted = action.highlighted,
                        onClick = action.onClick,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ListenTimeReadout(
    positionMillis: Long,
    durationMillis: Long,
    active: Boolean,
    cancelArmed: Boolean,
) {
    val color by animateColorAsState(
        targetValue = when {
            cancelArmed -> MaterialTheme.colorScheme.onSurfaceVariant
            active -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "listen-readout-color",
    )
    val totalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val posText = formatTimeMs(positionMillis)
    val durText = if (durationMillis > 0L) formatTimeMs(durationMillis) else ""
    val annotated = remember(posText, durText, totalColor) {
        buildAnnotatedString {
            append(posText)
            if (durText.isNotEmpty()) {
                withStyle(SpanStyle(color = totalColor)) {
                    append(" / ")
                    append(durText)
                }
            }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.titleLargeEmphasized.tabular,
        color = color,
        maxLines = 1,
    )
}

@Composable
internal fun ListenStaticCover(
    coverUrl: String,
    maxWidth: Dp,
    maxHeight: Dp,
    minSide: Dp = 140.dp,
) {
    val side = (minOf(maxWidth, maxHeight) * 0.80f).coerceIn(minSide.coerceAtMost(280.dp), 280.dp)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.size(side),
    ) {
        if (coverUrl.isNotBlank()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Headphones,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(side * 0.36f),
                )
            }
        }
    }
}

@Composable
private fun ListenVinylDisc(
    coverUrl: String,
    discSize: Dp,
    isPlaying: Boolean,
    speed: Float,
    positionMillisProvider: () -> Long,
    durationMillis: Long,
    isScrubbing: Boolean,
    cancelArmed: Boolean,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    onCancelArmedChange: (Boolean) -> Unit,
    onScrubCancel: () -> Unit,
) {
    val reducedMotion = LocalPikoMotion.current.reduced
    val windowVisible = LocalWindowVisible.current
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error

    var discAngle by remember { mutableFloatStateOf(0f) }
    var discOmega by remember { mutableFloatStateOf(0f) }
    var scrubDeltaDegrees by remember { mutableFloatStateOf(0f) }
    var discGestureActive by remember { mutableStateOf(false) }

    val latestPos by rememberUpdatedState(positionMillisProvider)
    val latestDur by rememberUpdatedState(durationMillis)
    val latestOnScrubStart by rememberUpdatedState(onScrubStart)
    val latestOnScrub by rememberUpdatedState(onScrub)
    val latestOnScrubEnd by rememberUpdatedState(onScrubEnd)
    val latestOnCancelArmed by rememberUpdatedState(onCancelArmedChange)
    val latestOnScrubCancel by rememberUpdatedState(onScrubCancel)

    // 30fps 节流转动 + 启停阻尼惯性；窗口不可见或减少动画时停止挂帧
    LaunchedEffect(isPlaying, speed, isScrubbing, reducedMotion, windowVisible) {
        if (reducedMotion || isScrubbing || !windowVisible) {
            discOmega = 0f
            return@LaunchedEffect
        }
        val targetOmega = if (isPlaying) {
            360f / (DiscRotationPeriodMillis / 1000f) * speed.coerceAtLeast(0.1f)
        } else {
            0f
        }
        if (targetOmega == 0f && abs(discOmega) < 0.05f) {
            discOmega = 0f
            return@LaunchedEffect
        }
        var lastNanos = 0L
        while (true) {
            val nowNanos = withFrameNanos { it }
            if (lastNanos == 0L) {
                lastNanos = nowNanos
                delay(DiscFrameDelayMillis)
                continue
            }
            val dt = ((nowNanos - lastNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            lastNanos = nowNanos
            val tau = if (targetOmega > discOmega) 0.35f else 0.45f
            val alpha = 1f - exp(-dt / tau)
            discOmega += (targetOmega - discOmega) * alpha
            discAngle = (discAngle + discOmega * dt) % 360f
            if (targetOmega == 0f && abs(discOmega) < 0.05f) {
                discOmega = 0f
                break
            }
            delay(DiscFrameDelayMillis)
        }
    }

    Box(
        modifier = Modifier
            .size(discSize)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = minOf(size.width, size.height) / 2f
                    if (radius <= 0f) return@awaitEachGesture
                    val slopChange = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        ?: return@awaitEachGesture
                    val dur = latestDur
                    if (dur <= 0L) return@awaitEachGesture
                    val basePos = latestPos()
                    var lastAngle = vectorDegrees(slopChange.position - center)
                    var totalTurn = 0f
                    var cancelled = false
                    var settled = false
                    discGestureActive = true
                    scrubDeltaDegrees = 0f
                    latestOnScrubStart()
                    try {
                        val completed = drag(slopChange.id) { change ->
                            val vec = change.position - center
                            val dist = hypot(vec.x, vec.y)
                            val nowCancelled = dist > radius * CancelRadiusRatio
                            if (nowCancelled != cancelled) {
                                cancelled = nowCancelled
                                latestOnCancelArmed(cancelled)
                            }
                            if (!cancelled && dist > radius * 0.12f) {
                                val angle = vectorDegrees(vec)
                                var delta = angle - lastAngle
                                if (delta > 180f) delta -= 360f
                                if (delta < -180f) delta += 360f
                                lastAngle = angle
                                totalTurn += delta
                                discAngle = (discAngle + delta) % 360f
                                scrubDeltaDegrees = totalTurn
                                // 一整圈（360°）对应 180 秒进度步进，大文件与短音频都手感均衡
                                val deltaMillis = (totalTurn / 360f * 180_000f).toLong()
                                latestOnScrub((basePos + deltaMillis).coerceIn(0L, dur))
                            }
                            change.consume()
                        }
                        settled = true
                        discGestureActive = false
                        scrubDeltaDegrees = 0f
                        if (!completed || cancelled) {
                            latestOnScrubCancel()
                        } else {
                            latestOnScrubEnd()
                        }
                    } finally {
                        if (!settled) {
                            discGestureActive = false
                            scrubDeltaDegrees = 0f
                            latestOnScrubCancel()
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // 旋转黑胶盘体、中心标签与中心轴孔（与 PlayerCustomizationPage 共用同一绘制实现）
        ListenVinylDiscVisual(
            coverUrl = coverUrl,
            discSize = discSize,
            rotationDegrees = discAngle,
            modifier = Modifier.fillMaxSize(),
        )

        // 转动唱片时的圆弧反馈
        if (discGestureActive && abs(scrubDeltaDegrees) > 1f) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                val arcColor = if (cancelArmed) error else primary
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = scrubDeltaDegrees.coerceIn(-359f, 359f),
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/**
 * 黑胶唱片盘体 + 中央标签纸 + 中心轴孔（由 [ListenMainContent] 统一驱动，保证真实聆听与预览 1:1 一致）。
 *
 * 无封面图时，中央标签围绕中心轴孔绘制对称极简黑胶唱片环纹，避免任何居中图标与黑色轴孔重叠冲突。
 */
@Composable
internal fun ListenVinylDiscVisual(
    coverUrl: String,
    discSize: Dp,
    rotationDegrees: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val haloColor = colors.primary.copy(alpha = 0.11f)
    val labelRingColor = colors.onPrimaryContainer.copy(alpha = 0.22f)
    val labelArcColor = colors.onPrimaryContainer.copy(alpha = 0.36f)
    val labelDotColor = colors.onPrimaryContainer.copy(alpha = 0.28f)
    val collarFillColor = colors.primary.copy(alpha = 0.14f)
    val collarStrokeColor = colors.onPrimaryContainer.copy(alpha = 0.28f)

    Box(
        modifier = modifier.size(discSize),
        contentAlignment = Alignment.Center,
    ) {
        // 旋转黑胶盘体与纹路
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotationDegrees },
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            drawCircle(color = haloColor, radius = r, center = center)
            val discRadius = r * 0.94f
            drawCircle(color = Color(0xFF15181F), radius = discRadius, center = center)
            for (i in 1..DiscGrooveCount) {
                val frac = DiscLabelFraction + (1f - DiscLabelFraction) * (i / (DiscGrooveCount + 1f))
                drawCircle(
                    color = Color.White.copy(alpha = 0.08f),
                    radius = discRadius * frac,
                    center = center,
                    style = Stroke(width = DiscGrooveWidthPx),
                )
            }
        }

        // 中央封面标签纸
        val labelSize = discSize * DiscLabelFraction
        Box(
            modifier = Modifier
                .size(labelSize)
                .graphicsLayer { rotationZ = rotationDegrees }
                .clip(CircleShape)
                .background(colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUrl.isNotBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // 极简黑胶唱片纸标签：外圈细环 + 上下对称唱盘弧线与定位圆点 + 轴孔衬环，与中心轴孔零冲突
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val r = size.minDimension / 2f
                    drawCircle(
                        color = labelRingColor,
                        radius = r * 0.86f,
                        center = center,
                        style = Stroke(width = 1.2.dp.toPx()),
                    )
                    val arcRadius = r * 0.65f
                    val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
                    val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
                    val arcStroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(
                        color = labelArcColor,
                        startAngle = -138f,
                        sweepAngle = 96f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = arcStroke,
                    )
                    drawArc(
                        color = labelArcColor,
                        startAngle = 42f,
                        sweepAngle = 96f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = arcStroke,
                    )
                    val dotRadius = 2.dp.toPx()
                    drawCircle(
                        color = labelDotColor,
                        radius = dotRadius,
                        center = Offset(center.x - arcRadius, center.y),
                    )
                    drawCircle(
                        color = labelDotColor,
                        radius = dotRadius,
                        center = Offset(center.x + arcRadius, center.y),
                    )
                    val collarRadius = r * 0.30f
                    drawCircle(
                        color = collarFillColor,
                        radius = collarRadius,
                        center = center,
                    )
                    drawCircle(
                        color = collarStrokeColor,
                        radius = collarRadius,
                        center = center,
                        style = Stroke(width = 1.2.dp.toPx()),
                    )
                }
            }
            // 黑胶中心轴孔
            Box(
                modifier = Modifier
                    .size(discSize * DiscHoleFraction)
                    .clip(CircleShape)
                    .background(Color(0xFF15181F))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ListenTransportRow(
    config: ListenLayoutConfig,
    isPlaying: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onTogglePlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekBySeconds: (Int) -> Unit,
    previewMode: Boolean = false,
    selectedSlot: CenterSlotPosition? = null,
    onSelectSlot: ((CenterSlotPosition) -> Unit)? = null,
    dropTargetSlot: CenterSlotPosition? = null,
    onSlotBounds: ((CenterSlotPosition, Rect) -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ListenSideSlotButton(
            slot = config.outerLeft,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onPrevious = onPrevious,
            onNext = onNext,
            onSeekBySeconds = onSeekBySeconds,
            previewMode = previewMode,
            selected = previewMode && selectedSlot == CenterSlotPosition.OuterLeft,
            dropHighlighted = previewMode && dropTargetSlot == CenterSlotPosition.OuterLeft,
            onSelect = { onSelectSlot?.invoke(CenterSlotPosition.OuterLeft) },
            onBounds = onSlotBounds?.let { cb -> { rect -> cb(CenterSlotPosition.OuterLeft, rect) } },
        )
        ListenSideSlotButton(
            slot = config.innerLeft,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onPrevious = onPrevious,
            onNext = onNext,
            onSeekBySeconds = onSeekBySeconds,
            previewMode = previewMode,
            selected = previewMode && selectedSlot == CenterSlotPosition.InnerLeft,
            dropHighlighted = previewMode && dropTargetSlot == CenterSlotPosition.InnerLeft,
            onSelect = { onSelectSlot?.invoke(CenterSlotPosition.InnerLeft) },
            onBounds = onSlotBounds?.let { cb -> { rect -> cb(CenterSlotPosition.InnerLeft, rect) } },
        )
        PlayPauseButton(
            visible = true,
            isPlaying = isPlaying,
            isLoading = false,
            indicator = null,
            size = DpSize(68.dp, 56.dp),
            iconSize = 28.dp,
            squareCorner = 18.dp,
            pressedCorner = 14.dp,
            surfaceStyle = if (config.translucentPlayButton) PlayButtonSurfaceStyle.ThemeTonalScrim else PlayButtonSurfaceStyle.ThemeFilled,
            onClick = onTogglePlayPause,
        )
        ListenSideSlotButton(
            slot = config.innerRight,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onPrevious = onPrevious,
            onNext = onNext,
            onSeekBySeconds = onSeekBySeconds,
            previewMode = previewMode,
            selected = previewMode && selectedSlot == CenterSlotPosition.InnerRight,
            dropHighlighted = previewMode && dropTargetSlot == CenterSlotPosition.InnerRight,
            onSelect = { onSelectSlot?.invoke(CenterSlotPosition.InnerRight) },
            onBounds = onSlotBounds?.let { cb -> { rect -> cb(CenterSlotPosition.InnerRight, rect) } },
        )
        ListenSideSlotButton(
            slot = config.outerRight,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onPrevious = onPrevious,
            onNext = onNext,
            onSeekBySeconds = onSeekBySeconds,
            previewMode = previewMode,
            selected = previewMode && selectedSlot == CenterSlotPosition.OuterRight,
            dropHighlighted = previewMode && dropTargetSlot == CenterSlotPosition.OuterRight,
            onSelect = { onSelectSlot?.invoke(CenterSlotPosition.OuterRight) },
            onBounds = onSlotBounds?.let { cb -> { rect -> cb(CenterSlotPosition.OuterRight, rect) } },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ListenSideSlotButton(
    slot: CenterSlotConfig,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeekBySeconds: (Int) -> Unit,
    previewMode: Boolean = false,
    selected: Boolean = false,
    dropHighlighted: Boolean = false,
    onSelect: () -> Unit = {},
    onBounds: ((Rect) -> Unit)? = null,
) {
    val action = slot.action
    val sizeDp = (44.dp * slot.scale.ratio).coerceIn(34.dp, 46.dp)
    val placeholderDp = (38.dp * slot.scale.ratio).coerceIn(32.dp, 38.dp)
    val primary = MaterialTheme.colorScheme.primary
    val boundsMod = if (previewMode && onBounds != null) {
        Modifier.onGloballyPositioned { coords ->
            if (coords.isAttached) onBounds(coords.boundsInWindow())
        }
    } else {
        Modifier
    }
    if (action == CenterSlotAction.None) {
        if (!previewMode) return
        val strokeColor = if (dropHighlighted || selected) primary else MaterialTheme.colorScheme.outlineVariant
        val strokeWidth = if (dropHighlighted) 2.5.dp else if (selected) 2.dp else 1.2.dp
        Box(
            modifier = boundsMod
                .size(placeholderDp)
                .clip(CircleShape)
                .background(if (dropHighlighted) primary.copy(alpha = 0.20f) else Color.Transparent)
                .clickable(onClick = onSelect)
                .handCursor(),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = strokeWidth.toPx()
                val radius = (size.minDimension - strokePx) / 2f
                if (radius > 0f) {
                    drawCircle(
                        color = strokeColor,
                        radius = radius,
                        style = Stroke(
                            width = strokePx,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                        ),
                    )
                }
            }
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                tint = if (dropHighlighted || selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size((placeholderDp * 0.44f).coerceIn(14.dp, 18.dp)),
            )
        }
        return
    }
    val iconSize = (sizeDp * 0.50f).coerceIn(18.dp, 22.dp)
    val enabled = previewMode || when (action) {
        CenterSlotAction.Previous -> hasPrevious
        CenterSlotAction.Next -> hasNext
        is CenterSlotAction.SeekBackward, is CenterSlotAction.SeekForward -> true
        CenterSlotAction.None -> false
    }
    val icon = when (action) {
        CenterSlotAction.Previous -> Icons.Filled.SkipPrevious
        CenterSlotAction.Next -> Icons.Filled.SkipNext
        is CenterSlotAction.SeekBackward -> Icons.Filled.FastRewind
        is CenterSlotAction.SeekForward -> Icons.Filled.FastForward
        CenterSlotAction.None -> return
    }
    val label = when (action) {
        CenterSlotAction.Previous -> stringResource(Res.string.player_custom_ctrl_previous)
        CenterSlotAction.Next -> stringResource(Res.string.player_custom_ctrl_next)
        is CenterSlotAction.SeekBackward -> stringResource(Res.string.player_custom_ctrl_seek_back)
        is CenterSlotAction.SeekForward -> stringResource(Res.string.player_custom_ctrl_seek_forward)
        CenterSlotAction.None -> ""
    }
    val onClick: () -> Unit = if (previewMode) {
        onSelect
    } else {
        when (action) {
            CenterSlotAction.Previous -> onPrevious
            CenterSlotAction.Next -> onNext
            is CenterSlotAction.SeekBackward -> ({ onSeekBySeconds(-action.seconds) })
            is CenterSlotAction.SeekForward -> ({ onSeekBySeconds(action.seconds) })
            CenterSlotAction.None -> ({})
        }
    }
    val selectMod = when {
        previewMode && dropHighlighted -> Modifier
            .background(primary.copy(alpha = 0.22f), CircleShape)
            .border(2.5.dp, primary, CircleShape)
        previewMode && selected -> Modifier.border(2.dp, primary, CircleShape)
        else -> Modifier
    }
    if (slot.showBackground) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            shapes = IconButtonDefaults.shapes(),
            modifier = boundsMod.size(sizeDp).then(selectMod).handCursor(enabled),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize))
        }
    } else {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            shapes = IconButtonDefaults.shapes(),
            modifier = boundsMod.size(sizeDp).then(selectMod).handCursor(enabled),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(iconSize))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ListenActionPill(
    icon: ImageVector,
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val isEInk = LocalAppearance.current.isEInk
    FilledTonalToggleButton(
        checked = highlighted,
        onCheckedChange = { onClick() },
        border = if (isEInk) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        modifier = Modifier.handCursor(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.tabular,
            maxLines = 1,
        )
    }
}

@Composable
private fun ListenVolumeControl(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(onClick = onToggleMute) {
            Icon(
                imageVector = if (volume <= 0.001f) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = stringResource(Res.string.player_custom_ctrl_volume),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = volume.coerceIn(0f, 1f),
            onValueChange = onVolumeChange,
            modifier = Modifier.width(88.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ListenQueueColumn(
    entries: List<PlaylistEntry>,
    currentFileId: String,
    isPlaying: Boolean,
    onSelect: (PlaylistEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val currentIndex = remember(entries, currentFileId) { entries.indexOfFirst { it.fileId == currentFileId } }
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex.coerceAtLeast(0))
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(Res.string.listen_queue_count_fmt, entries.size),
            style = MaterialTheme.typography.titleMediumEmphasized.tabular,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(Res.string.listen_empty_queue),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(entries, key = { _, entry -> entry.fileId }) { index, entry ->
                    val selected = entry.fileId == currentFileId
                    Surface(
                        onClick = { onSelect(entry) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier.width(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected && isPlaying) {
                                    Icon(
                                        imageVector = Icons.Filled.GraphicEq,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                } else {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.labelMedium.tabular,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Text(
                                text = entry.label.ifBlank { entry.name.substringBeforeLast('.') },
                                style = if (selected) MaterialTheme.typography.bodyMediumEmphasized else MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListenSleepOptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun vectorDegrees(offset: Offset): Float =
    (atan2(offset.y.toDouble(), offset.x.toDouble()) * (180.0 / PI)).toFloat()

private fun formatListenSpeed(speed: Float): String {
    val hundredths = (speed * 100f).toInt()
    return when {
        hundredths % 100 == 0 -> "${hundredths / 100}.0x"
        hundredths % 10 == 0 -> "${hundredths / 100}.${(hundredths / 10) % 10}x"
        else -> "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}x"
    }
}

private fun formatCountdown(totalSeconds: Int): String {
    val clamped = totalSeconds.coerceAtLeast(0)
    val m = clamped / 60
    val s = clamped % 60
    return "${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
}

private fun listenDigitKey(key: Key): Int? = when (key) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}
