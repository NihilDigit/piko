package dev.piko.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

internal const val SpeedHorizontalBlockStepDp = 76f
private val SPEED_SETTLE_WINDOW = 400.milliseconds
internal const val SpeedVerticalBlockStepDp = 46f
internal const val SpeedVerticalDragStepDp = 36f
internal const val SpeedHorizontalDragStepDp = 56f
internal const val SpeedModeStepDp = 32f
internal const val SpeedSelectionIntentDp = 10f

internal val SpeedDialPresets = listOf(50, 100, 150, 200, 250, 300, 350)

/** 拨盘越过 3.5x 后的达芬奇风格「∞ 粗剪速览」档位哨兵值。 */
internal const val RAPID_SCRUB_VALUE = Int.MAX_VALUE

internal fun speedHundredths(speed: Float): Int = (speed * 100).roundToInt().coerceIn(25, 350)

internal fun speedNumber(value: Int): String = when {
    value == RAPID_SCRUB_VALUE -> "∞"
    value % 100 == 0 -> (value / 100).toString()
    value % 10 == 0 -> "${value / 100}.${value % 100 / 10}"
    else -> "${value / 100}.${(value % 100).toString().padStart(2, '0')}"
}

internal fun speedChoiceLabel(value: Int): String =
    if (value == RAPID_SCRUB_VALUE) "∞" else "${speedNumber(value)}x"

internal data class SpeedSelection(
    val value: Int,
    val choices: List<Int>,
    val position: Float,
    val temporary: Boolean,
    val locked: Boolean = false,
    val dockEnd: Boolean = false,
    val dockTop: Boolean = false,
    val showDial: Boolean = true,
) {
    val isRapidScrub: Boolean get() = value == RAPID_SCRUB_VALUE
    val speed: Float get() = if (isRapidScrub) MAX_SPEED else value / 100f

    fun coarseAt(position: Float): SpeedSelection {
        val bounded = position.coerceIn(0f, choices.lastIndex.toFloat())
        val nextValue = choices[bounded.roundToInt()]
        return copy(
            position = bounded,
            value = nextValue,
            locked = if (nextValue == RAPID_SCRUB_VALUE) false else locked,
        )
    }

    fun snapped(): SpeedSelection {
        val index = choices.indexOf(value).takeIf { it >= 0 } ?: position.roundToInt().coerceIn(0, choices.lastIndex)
        return copy(position = index.toFloat(), value = choices[index])
    }

    fun nearestTo(target: Int): SpeedSelection {
        val nearest = choices.filter { it != RAPID_SCRUB_VALUE }.minByOrNull { abs(it - target) } ?: value
        return copy(position = choices.indexOf(nearest).toFloat(), value = nearest)
    }

    companion object {
        fun start(
            initial: Float,
            temporary: Boolean,
            dockEnd: Boolean = false,
            dockTop: Boolean = false,
            includeRapidScrub: Boolean = false,
            showDial: Boolean = true,
        ): SpeedSelection {
            val value = speedHundredths(initial)
            val baseChoices = (SpeedDialPresets + value).distinct().sorted()
            val choices = if (includeRapidScrub) baseChoices + RAPID_SCRUB_VALUE else baseChoices
            return SpeedSelection(
                value = value,
                choices = choices,
                position = choices.indexOf(value).toFloat(),
                temporary = temporary,
                dockEnd = dockEnd,
                dockTop = dockTop,
                showDial = showDial,
            )
        }
    }
}

/** 先识别主方向，再按手指增量位移纯跟手换档，横滑/侧滑跨轴切换锁定，往返转向即时响应。 */
internal class SpeedSelectionDrag(
    private val stepDp: Float = SpeedVerticalDragStepDp,
) {
    private enum class Axis { Along, Across }

    private var axis: Axis? = null
    private var lastAlong = 0f
    private var probeAlong = 0f
    private var probeAcross = 0f
    private var crossStartAlong = 0f
    private var crossStartAcross = 0f
    private var engaged = false
    private var anchorInitialized = true

    fun resetAnchor() {
        axis = null
        engaged = false
        anchorInitialized = false
    }

    fun move(
        selection: SpeedSelection,
        along: Float,
        across: Float,
    ): SpeedSelection {
        if (!anchorInitialized) {
            anchorInitialized = true
            lastAlong = along
            probeAlong = along
            probeAcross = across
            crossStartAlong = along
            crossStartAcross = across
            return selection
        }
        val deltaAlong = along - lastAlong
        lastAlong = along
        val localAlong = along - probeAlong
        val localAcross = across - probeAcross
        val alongIntent = abs(localAlong) >= SpeedSelectionIntentDp && abs(localAlong) >= abs(localAcross) * 1.15f
        val crossIntent = abs(localAcross) >= 14f && abs(localAcross) >= abs(localAlong) * 1.25f

        if (axis != Axis.Across && crossIntent) {
            axis = Axis.Across
            crossStartAlong = probeAlong
            crossStartAcross = probeAcross
        } else if (axis == Axis.Across && abs(localAlong) >= SpeedSelectionIntentDp &&
            abs(localAlong) >= abs(localAcross) * 1.8f
        ) {
            reset(along, across)
            return selection.snapped()
        }

        if (axis == Axis.Across) {
            val cross = across - crossStartAcross
            val clearCross = abs(cross) >= SpeedModeStepDp &&
                abs(cross) >= abs(along - crossStartAlong) * 1.25f
            val next = when {
                selection.isRapidScrub -> null
                clearCross && selection.locked && cross < 0 -> selection.snapped().copy(locked = false)
                clearCross && !selection.locked && cross > 0 -> selection.snapped().copy(locked = true)
                else -> null
            }
            if (next != null) {
                reset(along, across)
                return next
            }
            if (abs(cross) < 8f) reset(along, across)
            else if (crossIntent) {
                probeAlong = along
                probeAcross = across
            }
            return selection
        }

        if (alongIntent) {
            axis = Axis.Along
            probeAlong = along
            probeAcross = across
        }
        if (axis != Axis.Along || selection.locked) return selection

        if (!engaged) {
            engaged = true
            val effective = localAlong - SpeedSelectionIntentDp * sign(localAlong)
            return selection.coarseAt(selection.position + effective / stepDp)
        }
        return selection.coarseAt(selection.position + deltaAlong / stepDp)
    }

    private fun reset(along: Float, across: Float) {
        axis = null
        lastAlong = along
        probeAlong = along
        probeAcross = across
        engaged = false
    }
}

/**
 * 播放器长按倍速选档与锁定控制器（与 Bilby 行为一致）：
 * - 长按触发临时倍速（默认为设置中的长按倍速），沿拨盘方向滑动跟手切档；
 * - 向锁定方向跨轴滑动锁定当前倍速，松手保持锁定并弹出「已锁定」胶囊；
 * - 未锁定直接松手则恢复长按前的基准倍速；已锁定状态下再次长按未重锁则解除锁定回到基准倍速。
 */
@Stable
internal class PlaybackSpeedController(
    private val readSpeed: () -> Float,
    private val writeSpeed: (Float) -> Unit,
    defaultHoldSpeed: Float = LONG_PRESS_BOOST_SPEED,
    private val readPositionMillis: () -> Long = { 0L },
    private val readDurationMillis: () -> Long = { 0L },
    private val onCommitRapidScrub: (Long) -> Unit = {},
    private val allowRapidScrub: () -> Boolean = { true },
    private val readIsPlaying: () -> Boolean = { false },
    private val onPauseForRapidScrub: () -> Unit = {},
    private val onResumeAfterRapidScrub: () -> Unit = {},
    var onDialSpeedWrite: (Float) -> Unit = {},
    private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
) {
    var selection by mutableStateOf<SpeedSelection?>(null)
        private set
    var speed by mutableFloatStateOf(readSpeed())
        private set
    var defaultHoldSpeed by mutableFloatStateOf(defaultHoldSpeed)
    /** 有效的长按加速倍率：配置值异常或 `<= 1.0x` 时一律回退到默认 `2.0x`，确保长按始终具备加速能力。 */
    val effectiveHoldSpeed: Float
        get() = defaultHoldSpeed.takeIf { it.isFinite() && it > 1.0f + 0.005f } ?: LONG_PRESS_BOOST_SPEED
    var lockedByHold by mutableStateOf(false)
        private set
    var lockedHint by mutableStateOf<Int?>(null)
        private set
    var hintRevision by mutableIntStateOf(0)
        private set
    var dragging by mutableStateOf(false)
        private set
    var coarseStepRevision by mutableIntStateOf(0)
        private set
    var rapidScrubPositionMillis by mutableLongStateOf(0L)
        private set
    var rapidScrubOriginMillis by mutableLongStateOf(0L)
        private set
    /** 松手或点选进入 `∞` 后的独立胶片浏览态：保持胶片视图打开直至用户点击画面跳转或取消。 */
    var rapidScrubBrowseMode by mutableStateOf(false)
        private set

    val active: Boolean get() = selection != null
    val isRapidScrubActive: Boolean get() = selection?.isRapidScrub == true

    /** 未被长按锁定的常驻基准倍速（如 1.0x 或面板设定的倍速）。 */
    private var baseSpeed = speed
    private var lockedHoldSpeed = speed
    private var preOpenSpeed = speed
    private var preOpenLocked = false
    private var lastWrittenSpeed = speed
    private var drag: SpeedSelectionDrag? = null
    private var wasPlayingBeforeScrub = false
    private var resumeQueuedByScrub = false
    private var settleUntil: ComparableTimeMark? = null

    fun sync(speed: Float) {
        if (active) return
        resumeQueuedByScrub = false
        val until = settleUntil
        if (until != null) {
            if (timeSource.markNow() < until) {
                if (abs(speed - lastWrittenSpeed) > 0.005f) return
            } else {
                settleUntil = null
            }
        }
        this.speed = speed
        lastWrittenSpeed = speed
        if (lockedByHold && abs(speed - lockedHoldSpeed) > 0.005f) {
            lockedByHold = false
            baseSpeed = speed
        } else if (!lockedByHold) {
            baseSpeed = speed
        }
    }

    /** 设置面板或快捷键显式修改常驻倍速：清除长按锁定态并以新倍速作为基准原速。 */
    fun applySpeed(target: Float) {
        if (active) cancel()
        settleUntil = null
        lockedByHold = false
        lockedHint = null
        baseSpeed = target
        speed = target
        flush(target, fromDial = false, force = true)
    }

    fun open(
        temporary: Boolean = false,
        dockEnd: Boolean = false,
        dockTop: Boolean = false,
        showDial: Boolean = true,
    ) {
        if (active) finish(cancel = true)
        resumeQueuedByScrub = false
        val observed = readSpeed()
        val settling = settleUntil?.let { timeSource.markNow() < it } == true
        val currentActual = if (settling && abs(observed - lastWrittenSpeed) > 0.005f) {
            lastWrittenSpeed
        } else {
            observed
        }
        preOpenSpeed = currentActual
        preOpenLocked = lockedByHold
        lastWrittenSpeed = currentActual
        if (!lockedByHold) baseSpeed = currentActual
        val initial = when {
            !temporary -> currentActual
            lockedByHold && currentActual > 1.0f + 0.005f -> currentActual
            else -> effectiveHoldSpeed
        }
        lockedHint = null
        val canRapidScrub = showDial && readDurationMillis() > 0L && allowRapidScrub()
        val started = SpeedSelection.start(
            initial = initial,
            temporary = temporary,
            dockEnd = dockEnd,
            dockTop = dockTop,
            includeRapidScrub = canRapidScrub,
            showDial = showDial,
        )
        selection = started
        speed = started.speed
        flush(started.speed, fromDial = true, force = true)
    }

    fun setRapidScrubPosition(millis: Long) {
        if (!isRapidScrubActive) return
        val dur = readDurationMillis()
        if (dur <= 0L) return
        rapidScrubPositionMillis = millis.coerceIn(0L, dur)
    }

    fun scrubRapidByFraction(deltaFraction: Float) {
        if (!isRapidScrubActive) return
        val dur = readDurationMillis()
        if (dur <= 0L) return
        val deltaMs = (deltaFraction * dur).toLong()
        rapidScrubPositionMillis = (rapidScrubPositionMillis + deltaMs).coerceIn(0L, dur)
    }

    fun scrubRapidByMillis(deltaMillis: Long) {
        if (!isRapidScrubActive) return
        val dur = readDurationMillis()
        if (dur <= 0L) return
        rapidScrubPositionMillis = (rapidScrubPositionMillis + deltaMillis).coerceIn(0L, dur)
    }

    /** 长按滑动松手收尾：停在 `∞` 时转入可自由翻阅的胶片浏览态，其余档位照常结束并按锁定态决定是否还原倍速。 */
    fun releaseHoldDrag() {
        val current = selection ?: return
        if (current.isRapidScrub) {
            dragging = false
            drag = null
            rapidScrubBrowseMode = true
        } else {
            finish(cancel = false)
        }
    }

    /** 胶片视图中确认跳转到指定时刻并退出粗剪模式。 */
    fun commitRapidScrub(targetMillis: Long = rapidScrubPositionMillis) {
        val dur = readDurationMillis()
        if (dur > 0L) {
            rapidScrubPositionMillis = targetMillis.coerceIn(0L, dur)
        }
        finish(cancel = false)
    }

    fun exitRapidScrubToMaxSpeed() {
        val current = selection ?: return
        if (!current.isRapidScrub) return
        rapidScrubBrowseMode = false
        if (dragging) {
            drag?.resetAnchor()
        } else {
            drag = null
        }
        val maxIdx = current.choices.indexOfLast { it != RAPID_SCRUB_VALUE }
        if (maxIdx >= 0) {
            update(current.coarseAt(maxIdx.toFloat()), allowUnlock = true)
        }
    }

    fun beginDrag(vertical: Boolean = true): Boolean {
        val current = selection ?: return false
        if (dragging || current.locked) return false
        dragging = true
        drag = SpeedSelectionDrag(
            stepDp = if (vertical) SpeedVerticalDragStepDp else SpeedHorizontalDragStepDp,
        )
        return true
    }

    fun move(alongDp: Float, acrossDp: Float) {
        val current = selection ?: return
        update(
            drag?.move(current, alongDp, acrossDp) ?: return,
            allowUnlock = true,
        )
    }

    fun step(direction: Int) {
        selection?.let { update(it.coarseAt(it.position + direction)) }
    }

    fun choose(value: Int) {
        if (dragging) return
        val current = selection ?: return
        val index = current.choices.indexOf(value)
        if (index < 0) return
        if (value == RAPID_SCRUB_VALUE) {
            update(current.coarseAt(index.toFloat()), allowUnlock = true)
            rapidScrubBrowseMode = true
            return
        }
        update(current.coarseAt(index.toFloat()).copy(locked = true))
        finish()
    }

    fun adjustTo(speed: Float) {
        selection?.let { update(it.nearestTo(speedHundredths(speed))) }
    }

    fun lock() {
        selection?.takeUnless { it.isRapidScrub }?.let { update(it.copy(locked = true)) }
    }

    fun finish(cancel: Boolean = false) {
        val current = selection ?: return
        val wasRapidScrub = current.isRapidScrub
        val shouldResumePlayback = wasRapidScrub && wasPlayingBeforeScrub
        wasPlayingBeforeScrub = false
        resumeQueuedByScrub = false
        val commitPosition = rapidScrubPositionMillis
        val finalSpeed = when {
            cancel -> {
                lockedByHold = preOpenLocked
                preOpenSpeed
            }
            wasRapidScrub -> {
                lockedByHold = preOpenLocked
                if (current.temporary && !preOpenLocked) baseSpeed else preOpenSpeed
            }
            current.locked -> {
                val chosen = current.speed
                if (current.temporary) {
                    val lockedAwayFromBase = abs(chosen - baseSpeed) > 0.005f
                    lockedByHold = lockedAwayFromBase
                    lockedHoldSpeed = if (lockedAwayFromBase) chosen else baseSpeed
                } else {
                    lockedByHold = false
                    baseSpeed = chosen
                }
                chosen
            }
            !current.temporary -> {
                val chosen = current.speed
                lockedByHold = false
                baseSpeed = chosen
                chosen
            }
            else -> {
                // 画面长按未锁定直接松手（含此前已处于长按锁定态时再次长按未重锁）：解除锁定回到基准原速。
                lockedByHold = false
                baseSpeed
            }
        }
        selection = null
        rapidScrubBrowseMode = false
        dragging = false
        drag = null
        speed = finalSpeed
        settleUntil = timeSource.markNow() + SPEED_SETTLE_WINDOW
        flush(finalSpeed, fromDial = true, force = true)
        if (!cancel && wasRapidScrub) {
            onCommitRapidScrub(commitPosition)
        } else if (!cancel && current.locked) {
            lockedHint = current.value
            hintRevision++
        }
        if (shouldResumePlayback) {
            onResumeAfterRapidScrub()
        }
    }

    fun cancel() = finish(cancel = true)

    /** 组件销毁或切集卸载时静默清理状态，不向正在关闭的播放器触发恢复播放。 */
    fun dispose() {
        wasPlayingBeforeScrub = false
        resumeQueuedByScrub = false
        finish(cancel = true)
    }

    fun clearHint() {
        lockedHint = null
    }

    private fun update(next: SpeedSelection, allowUnlock: Boolean = false) {
        val previous = selection
        if (previous?.locked == true && !allowUnlock) return
        if (previous == next) return
        val enteredRapidScrub = previous?.isRapidScrub != true && next.isRapidScrub
        val exitedRapidScrub = previous?.isRapidScrub == true && !next.isRapidScrub
        if (enteredRapidScrub) {
            val origin = readPositionMillis().coerceIn(0L, readDurationMillis().coerceAtLeast(0L))
            rapidScrubOriginMillis = origin
            rapidScrubPositionMillis = origin
            val playingNow = readIsPlaying() || resumeQueuedByScrub
            resumeQueuedByScrub = false
            wasPlayingBeforeScrub = playingNow
            // 进入速览前先切回基准倍速再暂停，避免暂停态下后端忽略降速或残留 3x/3.5x
            speed = baseSpeed
            flush(baseSpeed, fromDial = true, force = true)
            if (playingNow) {
                onPauseForRapidScrub()
            }
        }
        selection = next
        if (dragging && !next.locked && previous?.value != next.value) {
            coarseStepRevision++
        }
        if (next.isRapidScrub) {
            // 进入速览时不以 3.5x 继续拉高带宽消耗，保持基准倍速
            speed = baseSpeed
            if (!enteredRapidScrub) {
                flush(baseSpeed, fromDial = true)
            }
        } else {
            speed = next.speed
            flush(next.speed, fromDial = true)
            if (exitedRapidScrub) {
                val shouldResume = wasPlayingBeforeScrub
                wasPlayingBeforeScrub = false
                if (shouldResume) {
                    resumeQueuedByScrub = true
                    onResumeAfterRapidScrub()
                }
            }
        }
    }

    private fun flush(value: Float, fromDial: Boolean = true, force: Boolean = false) {
        if (force || abs(lastWrittenSpeed - value) > 0.0001f) {
            lastWrittenSpeed = value
            if (fromDial) onDialSpeedWrite(value)
            writeSpeed(value)
        }
    }
}

@Composable
internal fun rememberPlaybackSpeedController(
    mediaKey: Any,
    playbackSpeed: Float?,
    defaultHoldSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    readPositionMillis: () -> Long = { 0L },
    readDurationMillis: () -> Long = { 0L },
    onCommitRapidScrub: (Long) -> Unit = {},
    allowRapidScrub: () -> Boolean = { true },
    readIsPlaying: () -> Boolean = { false },
    onPauseForRapidScrub: () -> Unit = {},
    onResumeAfterRapidScrub: () -> Unit = {},
): PlaybackSpeedController {
    val latestSpeed by rememberUpdatedState(playbackSpeed ?: 1f)
    val latestOnSpeedChange by rememberUpdatedState(onSpeedChange)
    val latestReadPosition by rememberUpdatedState(readPositionMillis)
    val latestReadDuration by rememberUpdatedState(readDurationMillis)
    val latestOnCommitRapidScrub by rememberUpdatedState(onCommitRapidScrub)
    val latestAllowRapidScrub by rememberUpdatedState(allowRapidScrub)
    val latestReadIsPlaying by rememberUpdatedState(readIsPlaying)
    val latestOnPauseForRapidScrub by rememberUpdatedState(onPauseForRapidScrub)
    val latestOnResumeAfterRapidScrub by rememberUpdatedState(onResumeAfterRapidScrub)
    val controller = remember(mediaKey) {
        PlaybackSpeedController(
            readSpeed = { latestSpeed },
            writeSpeed = { latestOnSpeedChange(it) },
            defaultHoldSpeed = defaultHoldSpeed,
            readPositionMillis = { latestReadPosition() },
            readDurationMillis = { latestReadDuration() },
            onCommitRapidScrub = { latestOnCommitRapidScrub(it) },
            allowRapidScrub = { latestAllowRapidScrub() },
            readIsPlaying = { latestReadIsPlaying() },
            onPauseForRapidScrub = { latestOnPauseForRapidScrub() },
            onResumeAfterRapidScrub = { latestOnResumeAfterRapidScrub() },
        )
    }
    controller.defaultHoldSpeed = defaultHoldSpeed
    LaunchedEffect(playbackSpeed) {
        playbackSpeed?.let(controller::sync)
    }
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    val window = LocalWindowInfo.current.containerSize
    val vertical = window.width > window.height
    LaunchedEffect(controller, vertical) { controller.cancel() }
    return controller
}
