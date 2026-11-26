package dev.piko.shared.media.player

import androidx.compose.runtime.Immutable
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.MediaService
import dev.piko.shared.media.PlayableMediaInfo
import dev.piko.shared.media.RandomAccessMediaSource
import dev.piko.shared.util.PikoLock
import dev.piko.shared.util.withPikoLock
import io.github.nihildigit.pikpak.AiSubtitleAudioFormat
import io.github.nihildigit.pikpak.AiSubtitleControl
import io.github.nihildigit.pikpak.AiSubtitleCue
import io.github.nihildigit.pikpak.AiSubtitleEvent
import io.github.nihildigit.pikpak.AiSubtitleLanguage
import io.github.nihildigit.pikpak.AiSubtitleQuota
import io.github.nihildigit.pikpak.AiSubtitleQuotaExceededException
import io.github.nihildigit.pikpak.AiSubtitleSession
import io.github.nihildigit.pikpak.AiSubtitleStart
import io.github.nihildigit.pikpak.PikPakException
import io.github.nihildigit.pikpak.aiSubtitleAudioTrackGcid
import io.github.nihildigit.pikpak.getAiSubtitleNode
import io.github.nihildigit.pikpak.openAiSubtitleSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * 平台音频解码接缝：将 [RandomAccessMediaSource] 的指定音轨解码并重采样为
 * 16 kHz 单声道 `pcm_s16le` 帧，供 `subsrelay` 实时 AI 字幕使用。
 */
interface AiSubtitlePcmDecoder {
    suspend fun open(
        source: RandomAccessMediaSource,
        audioStreamIndex: Int = -1,
    ): AiSubtitlePcmReader
}

interface AiSubtitlePcmReader : AutoCloseable {
    suspend fun seekTo(positionMs: Long)

    /** 读取下一段约 100 ms 的 16 kHz 单声道 `pcm_s16le` 帧；返回 null 表示到达流尾。 */
    suspend fun readFrame(): AiSubtitlePcmFrame?
}

class AiSubtitlePcmFrame(
    val positionMs: Long,
    val pcm: ByteArray,
)

enum class AiSubtitleStatus { Idle, Connecting, Live, Error }

@Immutable
data class AiSubtitleUiState(
    val available: Boolean = false,
    val on: Boolean = false,
    /** 是否开启「按需启用」（本地 Silero VAD 跳过无对白段以节省额度），默认开启。 */
    val onDemand: Boolean = true,
    /** 本地 Silero VAD 累计为用户节省的净额度秒数。 */
    val savedSeconds: Long = 0L,
    /** 是否正在施加智能播放等待锁（等待首句 AI 字幕就绪再续播）。 */
    val isHoldingPlayback: Boolean = false,
    val status: AiSubtitleStatus = AiSubtitleStatus.Idle,
    /** 目标语言码；空串表示原语言转写。 */
    val language: String = "",
    val sourceLanguage: String = "",
    val supportedLanguages: List<AiSubtitleLanguage> = emptyList(),
    val quota: AiSubtitleQuota? = null,
    val errorMessage: String? = null,
    val activeCueText: String? = null,
)

/**
 * 将任意采样率与声道数的解码音频（归一到 -1f..1f 浮点单声道）线性重采样为
 * 16 kHz 单声道 `pcm_s16le` 的 100 ms 固定帧（每帧 1600 样本 = 3200 字节）。
 */
class AiSubtitlePcmResampler(
    private val targetSampleRate: Int = AiSubtitleAudioFormat.SAMPLE_RATE,
    frameDurationMs: Int = AiSubtitleAudioFormat.RECOMMENDED_FRAME_MS,
) {
    private val frameSamples = (targetSampleRate * frameDurationMs) / 1000
    private val outBuffer = ShortArray(frameSamples)
    private var outFilled = 0
    private var frameStartMs = 0L
    private var hasFrameStart = false

    private var prevSample = 0f
    private var hasPrev = false
    private var fracPos = 0.0

    fun reset() {
        outFilled = 0
        hasFrameStart = false
        hasPrev = false
        fracPos = 0.0
    }

    /**
     * 喂入一段单声道浮点样本 [monoSamples]（范围约 -1f..1f），首样本时间轴位置为 [ptsMs]，
     * 原采样率为 [inputSampleRate]。产出的完整 100 ms 帧追加到 [outFrames]。
     */
    fun pushMonoFloat(
        monoSamples: FloatArray,
        sampleCount: Int,
        inputSampleRate: Int,
        ptsMs: Long,
        outFrames: MutableList<AiSubtitlePcmFrame>,
    ) {
        if (sampleCount <= 0 || inputSampleRate <= 0) return
        val step = inputSampleRate.toDouble() / targetSampleRate.toDouble()
        var pos = fracPos
        if (!hasPrev) {
            prevSample = monoSamples[0]
            hasPrev = true
            pos = 0.0
        }
        while (pos < sampleCount) {
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val s0 = if (idx == 0) prevSample else monoSamples[idx - 1]
            val s1 = monoSamples[idx]
            val interp = s0 + (s1 - s0) * frac
            val clamped = interp.coerceIn(-1f, 1f)
            val s16 = (clamped * 32767f).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            if (!hasFrameStart) {
                val offsetMs = ((pos * 1000.0) / inputSampleRate.toDouble()).toLong()
                frameStartMs = (ptsMs + offsetMs).coerceAtLeast(0L)
                hasFrameStart = true
            }
            outBuffer[outFilled++] = s16
            if (outFilled == frameSamples) {
                outFrames += AiSubtitlePcmFrame(
                    positionMs = frameStartMs,
                    pcm = shortsToLittleEndianBytes(outBuffer, frameSamples),
                )
                outFilled = 0
                hasFrameStart = false
            }
            pos += step
        }
        prevSample = monoSamples[sampleCount - 1]
        fracPos = pos - sampleCount
    }

    /** 冲刷尾部不足一帧的剩余样本（流尾时调用）。 */
    fun flush(outFrames: MutableList<AiSubtitlePcmFrame>) {
        if (outFilled > 0 && hasFrameStart) {
            outFrames += AiSubtitlePcmFrame(
                positionMs = frameStartMs,
                pcm = shortsToLittleEndianBytes(outBuffer, outFilled),
            )
            outFilled = 0
            hasFrameStart = false
        }
    }

    private fun shortsToLittleEndianBytes(samples: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = samples[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        return bytes
    }
}

/**
 * 线程安全的单轨字幕时间轴存储，严格保证任意时刻最多展示一条不重叠字幕：
 * - 近同起点（`<= 250 ms`）且强重叠的后续片段视为流式修订，按最新到达替换（消除句首闪字）；
 * - 任意相邻两句发生时间重叠时，前句显示结束时间一律裁切至后句起点，杜绝双行叠字。
 */
internal class AiSubtitleCueStore {
    private val lock = PikoLock()
    private val cues = ArrayList<AiSubtitleCue>()

    fun clear() = withPikoLock(lock) {
        cues.clear()
    }

    fun add(cue: AiSubtitleCue) = withPikoLock(lock) {
        val cleaned = cue.text.trim()
        if (cleaned.isEmpty()) return@withPikoLock
        val normalized = cue.copy(
            endMs = maxOf(cue.startMs, cue.endMs),
            text = cleaned,
        )
        // 近同起点且区间高度重叠（如 Soniox 流式修订漂移 100~200ms）-> 以最新到达为准替换
        val revIdx = cues.indexOfFirst { existing ->
            abs(existing.startMs - normalized.startMs) <= REVISION_START_TOLERANCE_MS &&
                (existing.text == normalized.text || hasStrongOverlap(existing, normalized))
        }
        if (revIdx >= 0) {
            cues.removeAt(revIdx)
        }
        val insertIdx = cues.indexOfFirst { it.startMs > normalized.startMs }
        // 相邻完全重复文本合并
        val prevIdx = if (insertIdx < 0) cues.lastIndex else insertIdx - 1
        if (prevIdx >= 0) {
            val prev = cues[prevIdx]
            if (prev.text == normalized.text && normalized.startMs <= prev.endMs + CUE_LINGER_MS) {
                if (normalized.endMs > prev.endMs) {
                    cues[prevIdx] = prev.copy(endMs = normalized.endMs)
                }
                return@withPikoLock
            }
        }
        if (insertIdx < 0) cues.add(normalized) else cues.add(insertIdx, normalized)
    }

    /** 检查是否已有覆盖 [positionMs] 附近（`[positionMs - 500ms, positionMs + 1500ms]`）的字幕，用于智能等待放行。 */
    fun hasCueNear(positionMs: Long): Boolean = withPikoLock(lock) {
        val windowStart = (positionMs - 500L).coerceAtLeast(0L)
        val windowEnd = positionMs + 1_500L
        cues.any { it.endMs >= windowStart && it.startMs <= windowEnd }
    }

    /** 返回在 [positionMs] 时刻应当显示的唯一字幕文本（严格半开不重叠区间 `[start, displayEnd)`），无字幕时返回 null。 */
    fun textAt(positionMs: Long): String? = withPikoLock(lock) {
        if (cues.isEmpty()) return@withPikoLock null
        var matched: String? = null
        for (i in cues.indices) {
            val cue = cues[i]
            if (positionMs < cue.startMs) break
            var displayEnd = cue.endMs + CUE_LINGER_MS
            // 单轨严格防重叠：无论重叠时长多少，前句一律在后句起点处截断
            val next = cues.getOrNull(i + 1)
            if (next != null && next.startMs < displayEnd) {
                displayEnd = next.startMs
            }
            if (positionMs < displayEnd) {
                matched = cue.text
            }
        }
        matched
    }

    private fun hasStrongOverlap(a: AiSubtitleCue, b: AiSubtitleCue): Boolean {
        val overlap = minOf(a.endMs, b.endMs) - maxOf(a.startMs, b.startMs)
        if (overlap <= 0L) return a.startMs == b.startMs
        val shorter = minOf(a.endMs - a.startMs, b.endMs - b.startMs).coerceAtLeast(1L)
        return overlap * 2L >= shorter
    }

    private companion object {
        const val REVISION_START_TOLERANCE_MS = 250L
        const val CUE_LINGER_MS = 180L
    }
}

/**
 * 内联 Silero VAD v5 三状态人声门控（`QUIET` -> `SPEECH` -> `TAIL`）。
 *
 * 用连续样本 FIFO 将 100 ms（1600 样本）传输帧与 Silero v5 的 32 ms（512 样本）推理窗对齐，
 * 不丢弃余数样本，且在 `QUIET` 态维护 320 ms 前导环形缓冲确保首字不被切掉。
 */
internal class SileroVadGate(
    private val vad: SileroVad?,
) {
    private enum class GateState { QUIET, SPEECH, TAIL }

    private var state = GateState.QUIET
    private var consecutiveSpeechWindows = 0
    private var consecutiveWhisperWindows = 0
    private var consecutiveQuietWindows = 0

    // 512 样本推理窗累积缓冲
    private val windowBuf = FloatArray(SileroVad.WINDOW_SAMPLES)
    private var windowFilled = 0

    fun reset() {
        vad?.reset()
        state = GateState.QUIET
        consecutiveSpeechWindows = 0
        consecutiveWhisperWindows = 0
        consecutiveQuietWindows = 0
        windowFilled = 0
    }

    /**
     * 评估一帧 16 kHz 单声道 `pcm_s16le` 音频是否属于应送入服务端的人声段。
     * 未提供 [SileroVad] 时直接放行全部帧。
     */
    fun evaluateFrame(pcm: ByteArray): Boolean {
        val detector = vad ?: return true
        val sampleCount = pcm.size / 2
        var sawActive = state != GateState.QUIET
        var i = 0
        while (i < sampleCount) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s16 = (hi shl 8) or lo
            windowBuf[windowFilled++] = s16 / 32768f
            if (windowFilled == SileroVad.WINDOW_SAMPLES) {
                val prob = detector.scoreWindow(windowBuf)
                windowFilled = 0
                if (advanceState(prob)) {
                    sawActive = true
                }
            }
            i++
        }
        return sawActive || state != GateState.QUIET
    }

    private fun advanceState(prob: Float): Boolean {
        when (state) {
            GateState.QUIET -> {
                consecutiveSpeechWindows = if (prob >= SPEECH_START_THRESHOLD) consecutiveSpeechWindows + 1 else 0
                consecutiveWhisperWindows = if (prob >= WHISPER_START_THRESHOLD) consecutiveWhisperWindows + 1 else 0
                if (consecutiveSpeechWindows >= SPEECH_START_WINDOWS || consecutiveWhisperWindows >= WHISPER_START_WINDOWS) {
                    state = GateState.SPEECH
                    consecutiveQuietWindows = 0
                    return true
                }
                return false
            }
            GateState.SPEECH -> {
                if (prob < SPEECH_CONTINUE_THRESHOLD) {
                    state = GateState.TAIL
                    consecutiveQuietWindows = 1
                }
                return true
            }
            GateState.TAIL -> {
                if (prob >= SPEECH_CONTINUE_THRESHOLD) {
                    state = GateState.SPEECH
                    consecutiveQuietWindows = 0
                    return true
                }
                consecutiveQuietWindows++
                if (consecutiveQuietWindows >= TAIL_HOLD_WINDOWS) {
                    state = GateState.QUIET
                    consecutiveSpeechWindows = 0
                    consecutiveWhisperWindows = 0
                    return false
                }
                return true
            }
        }
    }

    private companion object {
        const val SPEECH_START_THRESHOLD = 0.35f    // 常规人声快通道：64ms 秒触发
        const val SPEECH_START_WINDOWS = 2
        const val WHISPER_START_THRESHOLD = 0.10f   // 轻声/气声慢通道：128ms 持续触发，过滤单窗噪声尖峰
        const val WHISPER_START_WINDOWS = 4
        const val SPEECH_CONTINUE_THRESHOLD = 0.15f
        const val TAIL_HOLD_WINDOWS = 32            // ~1.0 s 尾随保持，防止轻声句尾被切
    }
}

private class TaggedAudioFrame(
    val positionMs: Long,
    val pcm: ByteArray,
    val isSpeech: Boolean,
)

/**
 * 播放器内实时 AI 字幕协调器（领先水位驱动 + 内联 Silero VAD 门控 + 语言秒切复用）。
 *
 * - 将「媒体位置（playhead）」「本地 VAD 扫描覆盖（scannedUntil）」「已推音频时间」分离；
 * - 落后目标水位（`playhead + speed × 10s`）时以 `3x` 受控追赶，稳态随倍速自然推进；
 * - 切目标语言时复用已打开的 `node.url`、`RandomAccessMediaSource`、`pcmReader` 及最近 30 秒本地音频缓存；
 * - 开启或 seek 时按需施加最长 5 秒的智能播放等待锁，VAD 判定落点无声时立刻 0 等待放行。
 */
class AiSubtitleController(
    private val repository: MediaService,
    private val decoder: AiSubtitlePcmDecoder?,
    private val scope: CoroutineScope,
    private val positionMillisProvider: () -> Long,
    private val isPlayingProvider: () -> Boolean,
    private val playbackSpeedProvider: () -> Float = { 1f },
    private val mediaInfoProvider: () -> PlayableMediaInfo?,
    private val currentFileIdProvider: () -> String,
    private val isLocalPlaybackProvider: () -> Boolean,
    private val audioTrackCountProvider: () -> Int,
    private val selectedAudioStreamIndexProvider: () -> Int,
    private val vadFactory: (() -> SileroVad?)? = null,
    private val onRequestPlaybackHold: ((Boolean) -> Unit)? = null,
    private val initialLanguage: String = "",
    private val onPersistLanguage: (suspend (String) -> Unit)? = null,
    private val initialOnDemand: Boolean = true,
    private val onPersistOnDemand: (suspend (Boolean) -> Unit)? = null,
    private val initialSavedSeconds: Long = 0L,
    private val onAddSavedSeconds: (suspend (Long) -> Unit)? = null,
) : AutoCloseable {

    private val _uiState = MutableStateFlow(
        AiSubtitleUiState(
            available = decoder != null,
            onDemand = initialOnDemand,
            savedSeconds = initialSavedSeconds.coerceAtLeast(0L),
            language = initialLanguage,
        ),
    )
    val uiState: StateFlow<AiSubtitleUiState> = _uiState.asStateFlow()

    private val cueStore = AiSubtitleCueStore()
    private val contextLock = PikoLock()

    // 媒体级长生命周期资源（切语言时保留复用，换视频/切音轨/关闭时释放）
    private class AudioContext(
        val fileId: String,
        val audioStreamIndex: Int,
        val effectiveGcid: String,
        val chosenMediaId: String?,
        val nodeUrl: String,
        val randomSource: RandomAccessMediaSource,
        val pcmReader: AiSubtitlePcmReader,
        val vadGate: SileroVadGate,
    ) : AutoCloseable {
        // 最近 30 秒已解码且已完成 VAD 标记的帧（约 960 KB），用于语言切换免 seek 即时回放
        val history = ArrayDeque<TaggedAudioFrame>(HISTORY_MAX_FRAMES)
        var scannedUntilMs: Long = 0L
        var eofReached: Boolean = false

        fun recordFrame(frame: TaggedAudioFrame) {
            if (history.size >= HISTORY_MAX_FRAMES) {
                history.removeFirst()
            }
            history.addLast(frame)
            scannedUntilMs = maxOf(scannedUntilMs, frame.positionMs + FRAME_MS)
        }

        fun resetForSeek(targetMs: Long) {
            history.clear()
            vadGate.reset()
            scannedUntilMs = targetMs
            eofReached = false
        }

        override fun close() {
            history.clear()
            runCatching { pcmReader.close() }
            runCatching { randomSource.close() }
        }
    }

    private var audioContext: AudioContext? = null
    private var sessionJob: Job? = null
    private var overlayJob: Job? = null
    private var generation = 0L

    // Seek 防抖信号：播放器显式 seek 时写入目标位置与时间，循环合并 200ms 内的连续拖动
    private var pendingSeekTargetMs: Long? = null
    private var pendingSeekMarkedAt: TimeSource.Monotonic.ValueTimeMark? = null

    // 智能等待锁状态（独立于用户手动暂停意图）
    private var holdActive = false
    private var holdLandingMs = 0L
    private var holdStartedAt: TimeSource.Monotonic.ValueTimeMark? = null
    private var userManuallyPaused = false

    fun enable(language: String = _uiState.value.language) {
        if (decoder == null) return
        userManuallyPaused = false
        _uiState.update {
            it.copy(
                on = true,
                language = language,
                errorMessage = null,
            )
        }
        acquirePlaybackHoldIfPlaying(positionMillisProvider().coerceAtLeast(0L))
        restartRelay(keepAudioContext = false)
    }

    fun disable() {
        generation++
        releasePlaybackHold()
        val prev = sessionJob
        sessionJob = null
        prev?.cancel()
        overlayJob?.cancel()
        overlayJob = null
        cueStore.clear()
        closeAudioContextAsync()
        _uiState.update {
            it.copy(
                on = false,
                status = AiSubtitleStatus.Idle,
                activeCueText = null,
                errorMessage = null,
            )
        }
    }

    fun setLanguage(language: String) {
        val prev = _uiState.value
        if (prev.language == language && prev.on) return
        _uiState.update { it.copy(language = language, errorMessage = null) }
        onPersistLanguage?.let { persist -> scope.launch { persist(language) } }
        if (prev.on) {
            acquirePlaybackHoldIfPlaying(positionMillisProvider().coerceAtLeast(0L))
            // 切目标语言保留 AudioContext（节点、流、解码器与 30s 本地缓冲），仅重建 WS 实现近秒切
            restartRelay(keepAudioContext = true)
        }
    }

    fun setOnDemand(enabled: Boolean) {
        if (_uiState.value.onDemand == enabled) return
        _uiState.update { it.copy(onDemand = enabled) }
        onPersistOnDemand?.let { persist -> scope.launch { persist(enabled) } }
    }

    /** 播放器显式 seek 时调用，驱动 200 ms 防抖 seek 与智能等待锁。 */
    fun onSeek(targetPositionMs: Long) {
        if (!_uiState.value.on) return
        val clamped = targetPositionMs.coerceAtLeast(0L)
        withPikoLock(contextLock) {
            pendingSeekTargetMs = clamped
            pendingSeekMarkedAt = TimeSource.Monotonic.markNow()
        }
        if (!cueStore.hasCueNear(clamped)) {
            acquirePlaybackHoldIfPlaying(clamped)
        }
    }

    /** 用户手动点播放/暂停时调用：用户主动暂停后，释放等待锁绝不恢复播放。 */
    fun onUserPauseIntentChanged(isPaused: Boolean) {
        userManuallyPaused = isPaused
        if (holdActive) {
            holdActive = false
            holdStartedAt = null
            _uiState.update { it.copy(isHoldingPlayback = false) }
        }
    }

    /** 切换视频、画质或音轨时调用；若 AI 字幕开启，则销毁旧 AudioContext 并为新上下文重建。 */
    fun onPlaybackContextChanged() {
        if (_uiState.value.on) {
            restartRelay(keepAudioContext = false)
        }
    }

    override fun close() {
        disable()
    }

    private fun acquirePlaybackHoldIfPlaying(landingMs: Long) {
        val callback = onRequestPlaybackHold ?: return
        if (userManuallyPaused) return
        if (!isPlayingProvider() && !holdActive) return
        holdLandingMs = landingMs
        holdStartedAt = TimeSource.Monotonic.markNow()
        if (!holdActive) {
            holdActive = true
            _uiState.update { it.copy(isHoldingPlayback = true) }
            scope.launch(Dispatchers.Main.immediate) { callback(true) }
        }
    }

    private fun releasePlaybackHold() {
        if (!holdActive) return
        holdActive = false
        holdStartedAt = null
        _uiState.update { it.copy(isHoldingPlayback = false) }
        if (!userManuallyPaused) {
            val callback = onRequestPlaybackHold ?: return
            scope.launch(Dispatchers.Main.immediate) { callback(false) }
        }
    }

    private fun closeAudioContextAsync() {
        val ctx = withPikoLock(contextLock) {
            val existing = audioContext
            audioContext = null
            existing
        }
        if (ctx != null) {
            scope.launch(Dispatchers.IO) { ctx.close() }
        }
    }

    private fun restartRelay(keepAudioContext: Boolean) {
        if (decoder == null) return
        val myGen = ++generation
        val prevSession = sessionJob
        overlayJob?.cancel()
        cueStore.clear()
        withPikoLock(contextLock) {
            pendingSeekTargetMs = null
            pendingSeekMarkedAt = null
        }
        _uiState.update { it.copy(status = AiSubtitleStatus.Connecting, activeCueText = null, errorMessage = null) }

        overlayJob = scope.launch {
            while (isActive && generation == myGen) {
                val pos = positionMillisProvider()
                val text = cueStore.textAt(pos)
                if (_uiState.value.activeCueText != text) {
                    _uiState.update { it.copy(activeCueText = text) }
                }
                if (holdActive) {
                    val elapsed = holdStartedAt?.elapsedNow()?.inWholeMilliseconds ?: Long.MAX_VALUE
                    if (cueStore.hasCueNear(holdLandingMs) || elapsed >= SMART_HOLD_DEADLINE_MS) {
                        releasePlaybackHold()
                    }
                }
                delay(OVERLAY_TICK_MS)
            }
        }

        sessionJob = scope.launch(Dispatchers.IO) {
            // 等待旧会话发完 stop 帧再建连，遵守单账号单并发限制
            prevSession?.cancelAndJoin()
            if (generation != myGen) return@launch
            if (!keepAudioContext) {
                closeAudioContext()
            }
            runRelaySessionWithRetry(myGen, _uiState.value.language)
        }
    }

    private fun closeAudioContext() {
        val old = withPikoLock(contextLock) {
            val c = audioContext
            audioContext = null
            c
        }
        old?.close()
    }

    private suspend fun CoroutineScope.runRelaySessionWithRetry(myGen: Long, targetLanguage: String) {
        var attempt = 0
        while (isActive && generation == myGen && _uiState.value.on) {
            val result = runRelaySession(myGen, targetLanguage)
            when (result) {
                RelayOutcome.Stopped -> return
                is RelayOutcome.PermanentFailure -> {
                    failSession(myGen, result.message)
                    return
                }
                is RelayOutcome.TransientFailure -> {
                    if (result.wasHealthy) attempt = 0
                    attempt++
                    closeAudioContext()
                    if (attempt > MAX_TRANSIENT_RETRIES || !isActive || generation != myGen || !_uiState.value.on) {
                        failSession(myGen, result.message)
                        return
                    }
                    val backoffMs = TRANSIENT_RETRY_BACKOFF_MS.getOrElse(attempt - 1) { TRANSIENT_RETRY_BACKOFF_MS.last() }
                    PikoLog.w(
                        AI_SUB_LOG_TAG,
                        "AI subtitle transient failure (attempt $attempt/$MAX_TRANSIENT_RETRIES), reconnecting in ${backoffMs}ms: ${result.logDetail}",
                    )
                    _uiState.update { it.copy(status = AiSubtitleStatus.Connecting, errorMessage = null) }
                    delay(backoffMs)
                }
            }
        }
    }

    private sealed interface RelayOutcome {
        data object Stopped : RelayOutcome
        data class PermanentFailure(val message: String) : RelayOutcome
        data class TransientFailure(val message: String, val logDetail: String, val wasHealthy: Boolean) : RelayOutcome
    }

    private suspend fun CoroutineScope.runRelaySession(myGen: Long, targetLanguage: String): RelayOutcome {
        var wsSession: AiSubtitleSession? = null
        var healthy = false
        try {
            if (isLocalPlaybackProvider()) {
                return RelayOutcome.PermanentFailure(tr("本地文件暂不支持 AI 字幕", "AI subtitles are not available for local files", "ローカルファイルは AI 字幕に対応していません", "로컬 파일은 AI 자막을 지원하지 않습니다", "本機檔案暫不支援 AI 字幕"))
            }
            val client = repository.currentAccountClient()
            if (client == null) {
                return RelayOutcome.PermanentFailure(tr("请先登录账号", "Sign in to use AI subtitles", "AI 字幕を使うにはログインしてください", "AI 자막을 사용하려면 로그인하세요", "請先登入帳號"))
            }
            if (client.isFreeAccount == true) {
                return RelayOutcome.PermanentFailure(tr("AI 字幕仅限 PikPak 会员使用", "AI subtitles require PikPak Premium", "AI 字幕は PikPak プレミアム限定です", "AI 자막은 PikPak 프리미엄 전용입니다", "AI 字幕僅限 PikPak 會員使用"))
            }

            val ctx = ensureAudioContext(myGen, client) ?: return RelayOutcome.Stopped
            if (generation != myGen) return RelayOutcome.Stopped

            val start = AiSubtitleStart(
                gcid = ctx.effectiveGcid,
                mediaId = ctx.chosenMediaId ?: ctx.fileId,
                fileId = ctx.fileId,
                targetLanguage = targetLanguage,
            )
            wsSession = client.openAiSubtitleSession(ctx.nodeUrl, start)
            if (generation != myGen) return RelayOutcome.Stopped

            val currentPlayPos = positionMillisProvider().coerceAtLeast(0L)
            val sendQueue = ArrayDeque<TaggedAudioFrame>(128)
            val preRoll = ArrayDeque<TaggedAudioFrame>(PREROLL_FRAMES + 1)

            // 切语言复用 AudioContext 时优先从 30s 本地历史缓冲回放，免去重新 CDN seek
            val replayStart = (currentPlayPos - REPLAY_BACKTRACK_MS).coerceAtLeast(0L)
            val canReplayFromHistory = ctx.history.isNotEmpty() &&
                ctx.history.first().positionMs <= currentPlayPos + 500L &&
                ctx.scannedUntilMs >= currentPlayPos
            if (canReplayFromHistory) {
                for (f in ctx.history) {
                    if (f.positionMs >= replayStart) {
                        sendQueue.addLast(f)
                    }
                }
            } else {
                ctx.pcmReader.seekTo(currentPlayPos)
                ctx.resetForSeek(currentPlayPos)
            }

            _uiState.update { it.copy(status = AiSubtitleStatus.Live, errorMessage = null) }
            // WS 建连就绪后重置智能等待计时起点，避免前期节点发现与 FFmpeg 打开耗时吃掉等首句的窗口
            if (holdActive) {
                holdLandingMs = currentPlayPos
                holdStartedAt = TimeSource.Monotonic.markNow()
            }
            PikoLog.i(
                AI_SUB_LOG_TAG,
                "AI subtitle session live: fileId=${ctx.fileId}, targetLang=${targetLanguage.ifBlank { "original" }}, startPosMs=$currentPlayPos, replayed=${canReplayFromHistory}, vad=${vadFactory != null}",
            )

            val activeWs = wsSession
            var loggedCueCount = 0
            var holdFirstSpeechMs = -1L
            val receiveJob = launch {
                for (event in activeWs.events) {
                    if (generation != myGen) break
                    when (event) {
                        is AiSubtitleEvent.Cue -> {
                            healthy = true
                            if (loggedCueCount < 5) {
                                loggedCueCount++
                                PikoLog.d(
                                    AI_SUB_LOG_TAG,
                                    "AI subtitle cue #$loggedCueCount: startMs=${event.cue.startMs}, endMs=${event.cue.endMs}, len=${event.cue.text.length}",
                                )
                            }
                            cueStore.add(event.cue)
                            if (holdActive) {
                                val targetCheckMs = if (holdFirstSpeechMs >= 0L) holdFirstSpeechMs else holdLandingMs
                                if (cueStore.hasCueNear(targetCheckMs) || cueStore.hasCueNear(holdLandingMs)) {
                                    releasePlaybackHold()
                                }
                            }
                        }
                        is AiSubtitleEvent.Quota -> _uiState.update { it.copy(quota = event.quota) }
                        is AiSubtitleEvent.Error -> {
                            PikoLog.w(AI_SUB_LOG_TAG, "subsrelay error: ${event.code} ${event.description}")
                            if (!event.isRecoverable) {
                                failSession(myGen, event.description.ifBlank { event.code })
                                break
                            }
                        }
                        is AiSubtitleEvent.Stop -> {
                            PikoLog.i(AI_SUB_LOG_TAG, "subsrelay stop: reason=${event.reason}, quotaExceeded=${event.isQuotaExceeded}")
                            if (event.isQuotaExceeded) {
                                failSession(myGen, tr("AI 字幕额度已用完", "AI subtitle quota reached", "AI 字幕の上限に達しました", "AI 자막 한도에 도달했습니다", "AI 字幕額度已用完"))
                            } else {
                                _uiState.update { it.copy(status = AiSubtitleStatus.Idle) }
                            }
                            break
                        }
                    }
                }
            }

            val clock = TimeSource.Monotonic
            var lastPlayPosMs = currentPlayPos
            var lastWallSend = clock.markNow()
            var lastSentPosMs = -1L
            var relayPausedForUser = false
            var relayPausedForSilence = false
            var silencePausedAt: TimeSource.Monotonic.ValueTimeMark? = null
            var silencePausedMediaMs = 0L
            var silentRunMs = 0L
            var sentFinalize = false
            var loggedFirstAudio = false
            var retryFrame: TaggedAudioFrame? = null
            var holdSpeechSeenAtLanding = false
            var currentPauseSkippedMs = 0L
            var pendingNetSavedMs = 0L

            fun flushSavedQuota(force: Boolean) {
                val wholeSeconds = pendingNetSavedMs / 1_000L
                if (wholeSeconds <= 0L) return
                if (!force && wholeSeconds < SAVED_FLUSH_STEP_SEC) return
                pendingNetSavedMs -= wholeSeconds * 1_000L
                _uiState.update { it.copy(savedSeconds = it.savedSeconds + wholeSeconds) }
                onAddSavedSeconds?.let { add -> scope.launch { add(wholeSeconds) } }
            }

            fun finalizePauseSavings() {
                // 扣除单次 PAUSE 约 5 秒收尾缓冲开销，仅累计真实净省时长
                val net = (currentPauseSkippedMs - PAUSE_OVERHEAD_MS).coerceAtLeast(0L)
                currentPauseSkippedMs = 0L
                if (net > 0L) {
                    pendingNetSavedMs += net
                    flushSavedQuota(force = false)
                }
            }

            try {
            while (isActive && generation == myGen && !activeWs.isClosed) {
                val playPosMs = positionMillisProvider().coerceAtLeast(0L)
                val speed = playbackSpeedProvider().coerceIn(0.5f, 4.0f)
                val playing = isPlayingProvider() || holdActive
                val onDemandActive = _uiState.value.onDemand && vadFactory != null

                // 1. 合并防抖后的显式 seek（或外部未走 onSeek 的大幅播放头跳变）
                val committedSeekMs = pollCommittedSeek(playPosMs, lastPlayPosMs)
                lastPlayPosMs = playPosMs
                if (committedSeekMs != null) {
                    PikoLog.d(AI_SUB_LOG_TAG, "AI subtitle seek: targetMs=$committedSeekMs")
                    sendQueue.clear()
                    preRoll.clear()
                    retryFrame = null
                    silentRunMs = 0L
                    sentFinalize = false
                    holdSpeechSeenAtLanding = false
                    holdFirstSpeechMs = -1L
                    if (relayPausedForSilence) {
                        finalizePauseSavings()
                        activeWs.sendControl(AiSubtitleControl.RESUME)
                        relayPausedForSilence = false
                    }
                    activeWs.sendControl(AiSubtitleControl.SEEK)
                    ctx.pcmReader.seekTo(committedSeekMs)
                    ctx.resetForSeek(committedSeekMs)
                    lastSentPosMs = -1L
                    lastWallSend = clock.markNow()
                    continue
                }

                // 2. 用户手动暂停（非智能等待锁）-> 通知服务端 PAUSE
                if (!playing) {
                    if (!relayPausedForUser && !relayPausedForSilence) {
                        relayPausedForUser = true
                        activeWs.sendControl(AiSubtitleControl.PAUSE)
                    }
                    delay(PACE_TICK_MS)
                    continue
                } else if (relayPausedForUser) {
                    relayPausedForUser = false
                    if (!relayPausedForSilence) {
                        activeWs.sendControl(AiSubtitleControl.RESUME)
                    }
                    lastWallSend = clock.markNow()
                }

                // 3. 领先水位驱动解码 + 内联 Silero VAD 扫描（目标覆盖 playhead + speed * 10s）
                val leadMs = (AI_LEAD_BASE_MS * speed).toLong().coerceIn(5_000L, 20_000L)
                val targetMediaPos = playPosMs + leadMs
                while (!ctx.eofReached && ctx.scannedUntilMs < targetMediaPos && sendQueue.size < MAX_LOOKAHEAD_FRAMES) {
                    // 检查是否有新的待处理 seek，若有则立刻跳出解码
                    if (hasPendingSeek()) break
                    val raw = ctx.pcmReader.readFrame()
                    if (raw == null) {
                        ctx.eofReached = true
                        break
                    }
                    val isSpeech = ctx.vadGate.evaluateFrame(raw.pcm)
                    val tagged = TaggedAudioFrame(raw.positionMs, raw.pcm, isSpeech)
                    ctx.recordFrame(tagged)
                    sendQueue.addLast(tagged)

                    // 智能等待锁：开启按需启用时若落点后 3 秒内 VAD 确认无语音，立即 0 等待放行画面；
                    // 若 3 秒内有语音，则保持等待直到该段首句字幕到达（或触达 6 秒上限）再续播，消除「播一会才出字」。
                    if (holdActive && onDemandActive) {
                        if (isSpeech && tagged.positionMs in holdLandingMs..(holdLandingMs + SMART_HOLD_SCAN_MS)) {
                            if (!holdSpeechSeenAtLanding) {
                                holdSpeechSeenAtLanding = true
                                holdFirstSpeechMs = tagged.positionMs
                            }
                        }
                        if (!holdSpeechSeenAtLanding && ctx.scannedUntilMs >= holdLandingMs + SMART_HOLD_SCAN_MS) {
                            PikoLog.d(AI_SUB_LOG_TAG, "AI subtitle smart-hold released early (non-speech landing at ${holdLandingMs}ms)")
                            releasePlaybackHold()
                        }
                    }
                }

                if (ctx.eofReached && sendQueue.isEmpty() && retryFrame == null) {
                    if (holdActive) releasePlaybackHold()
                    if (!sentFinalize) {
                        sentFinalize = true
                        activeWs.sendControl(AiSubtitleControl.FINALIZE)
                    }
                    delay(200L)
                    continue
                }

                // 4. 取出下一帧待评估/发送的音频（失败未入队的帧优先重试，绝不丢帧）
                val nextFrame = retryFrame ?: sendQueue.removeFirstOrNull()
                if (nextFrame == null) {
                    delay(PACE_TICK_MS)
                    continue
                }
                retryFrame = null

                // 5. 内联 VAD 门控与前瞻合并决策（仅在开启「按需启用」时跳过无对白段）
                if (onDemandActive && !nextFrame.isSpeech) {
                    if (preRoll.size >= PREROLL_FRAMES) preRoll.removeFirst()
                    preRoll.addLast(nextFrame)

                    val upcomingSpeech = !relayPausedForSilence && hasSpeechWithinLookahead(sendQueue, nextFrame.positionMs)
                    if (relayPausedForSilence) {
                        currentPauseSkippedMs += FRAME_MS
                        // 超过 5s 惯性开销后实时把净省秒数反映到面板，不必等到下一句才更新
                        if (currentPauseSkippedMs > PAUSE_OVERHEAD_MS) {
                            val netChunk = currentPauseSkippedMs - PAUSE_OVERHEAD_MS
                            currentPauseSkippedMs = PAUSE_OVERHEAD_MS
                            pendingNetSavedMs += netChunk
                            flushSavedQuota(force = false)
                        }
                        continue
                    }
                    if (!upcomingSpeech) {
                        silentRunMs += FRAME_MS
                        if (silentRunMs >= SILENCE_PAUSE_AFTER_MS) {
                            relayPausedForSilence = true
                            silencePausedAt = clock.markNow()
                            silencePausedMediaMs = nextFrame.positionMs
                            currentPauseSkippedMs = 0L
                            activeWs.sendControl(AiSubtitleControl.PAUSE)
                            PikoLog.d(AI_SUB_LOG_TAG, "AI subtitle VAD paused relay at ${nextFrame.positionMs}ms (silentRun=${silentRunMs}ms)")
                            continue
                        }
                    } else {
                        silentRunMs = 0L
                    }
                } else {
                    silentRunMs = 0L
                    if (relayPausedForSilence) {
                        val pausedWallMs = silencePausedAt?.elapsedNow()?.inWholeMilliseconds ?: 0L
                        val skippedMediaMs = (nextFrame.positionMs - silencePausedMediaMs).coerceAtLeast(0L)
                        finalizePauseSavings()
                        relayPausedForSilence = false
                        silencePausedAt = null
                        activeWs.sendControl(AiSubtitleControl.RESUME)
                        activeWs.sendControl(AiSubtitleControl.SEEK)
                        PikoLog.d(
                            AI_SUB_LOG_TAG,
                            "AI subtitle VAD resumed relay at ${nextFrame.positionMs}ms (skippedMedia=${skippedMediaMs}ms, pausedWall=${pausedWallMs}ms, preroll=${preRoll.size})",
                        )
                        while (preRoll.isNotEmpty()) {
                            sendQueue.addFirst(preRoll.removeLast())
                        }
                        sendQueue.addFirst(nextFrame)
                        lastWallSend = clock.markNow()
                        continue
                    }
                    preRoll.clear()
                }

                // 6. 受控追赶节拍：落后于 targetMediaPos 时按 3x（33ms/100ms 帧）快推，稳态自然跟随倍速
                val catchupNeeded = nextFrame.positionMs + 500L < targetMediaPos
                val minIntervalMs = if (catchupNeeded) CATCHUP_FRAME_INTERVAL_MS else (FRAME_MS / speed).toLong().coerceIn(25L, 200L)
                val elapsedMs = lastWallSend.elapsedNow().inWholeMilliseconds
                if (elapsedMs < minIntervalMs) {
                    delay(minIntervalMs - elapsedMs)
                }

                if (activeWs.trySendAudio(nextFrame.positionMs, nextFrame.pcm)) {
                    if (!loggedFirstAudio) {
                        loggedFirstAudio = true
                        PikoLog.d(AI_SUB_LOG_TAG, "AI subtitle first audio frame sent: framePosMs=${nextFrame.positionMs}, playPosMs=$playPosMs")
                    }
                    lastSentPosMs = nextFrame.positionMs + FRAME_MS
                    lastWallSend = clock.markNow()
                } else {
                    retryFrame = nextFrame
                    delay(PACE_TICK_MS)
                }
            }
            } finally {
                if (relayPausedForSilence) finalizePauseSavings()
                flushSavedQuota(force = true)
            }

            receiveJob.cancel()
            return RelayOutcome.Stopped
        } catch (e: CancellationException) {
            throw e
        } catch (e: PikPakException) {
            val desc = e.errorDescription?.takeIf { it.isNotBlank() } ?: e.errorMessage
            val isPermanent = e is AiSubtitleQuotaExceededException || e.errorCode in 400..499
            if (isPermanent) {
                PikoLog.w(AI_SUB_LOG_TAG, "AI subtitle session failed permanently: ${e.message}")
                return RelayOutcome.PermanentFailure(desc)
            }
            return RelayOutcome.TransientFailure(
                message = desc,
                logDetail = e.message ?: desc,
                wasHealthy = healthy,
            )
        } catch (t: Throwable) {
            val detail = "${t::class.simpleName}: ${t.message}"
            return RelayOutcome.TransientFailure(
                message = tr("AI 字幕连接失败，请重试", "Failed to load AI subtitles", "AI 字幕の読み込みに失敗しました", "AI 자막을 불러오지 못했습니다", "AI 字幕連線失敗，請重試"),
                logDetail = detail,
                wasHealthy = healthy,
            )
        } finally {
            if (generation == myGen) {
                releasePlaybackHold()
            }
            withContext(NonCancellable) {
                runCatching { wsSession?.closeGracefully(3.seconds) }
            }
        }
    }

    private suspend fun ensureAudioContext(myGen: Long, client: io.github.nihildigit.pikpak.PikPakClient): AudioContext? {
        val fileId = currentFileIdProvider()
        val info = mediaInfoProvider()
        val rawGcid = info?.gcid?.trim().orEmpty()
        if (fileId.isBlank() || rawGcid.length != 40) {
            failSession(myGen, tr("当前视频不支持 AI 字幕", "AI subtitles are unavailable for this video", "この動画は AI 字幕に対応していません", "이 동영상은 AI 자막을 지원하지 않습니다", "目前影片不支援 AI 字幕"))
            return null
        }
        val audioCount = audioTrackCountProvider()
        val selectedAudioIdx = selectedAudioStreamIndexProvider()
        val isMultiTrack = audioCount > 1 && selectedAudioIdx >= 0
        val streamOrdinal = if (isMultiTrack) selectedAudioIdx else -1

        val existing = withPikoLock(contextLock) { audioContext }
        if (existing != null && existing.fileId == fileId && existing.audioStreamIndex == streamOrdinal) {
            return existing
        }
        if (existing != null) {
            withPikoLock(contextLock) { if (audioContext === existing) audioContext = null }
            existing.close()
        }

        val effectiveGcid = if (isMultiTrack && selectedAudioIdx > 0) {
            aiSubtitleAudioTrackGcid(rawGcid, selectedAudioIdx)
        } else {
            rawGcid.uppercase()
        }
        val chosenMediaId: String? = if (!isMultiTrack) {
            info?.availableVariants
                ?.filter { !it.isOrigin && it.link.url.isNotBlank() && it.mediaId.isNotBlank() }
                ?.minByOrNull { it.video?.height?.takeIf { h -> h > 0 } ?: Int.MAX_VALUE }
                ?.mediaId
        } else {
            null
        }

        val node = try {
            client.getAiSubtitleNode(effectiveGcid)
        } catch (e: AiSubtitleQuotaExceededException) {
            _uiState.update { it.copy(quota = e.node.quota) }
            failSession(myGen, tr("本月 AI 字幕额度已用完", "AI subtitle quota used up", "AI 字幕の上限に達しました", "AI 자막 할당량을 모두 사용했습니다", "本月 AI 字幕額度已用完"))
            return null
        }
        if (generation != myGen) return null

        _uiState.update {
            it.copy(
                sourceLanguage = node.sourceLanguage,
                supportedLanguages = node.supportedLanguages,
                quota = node.quota ?: it.quota,
            )
        }

        val randomSource = repository.openRandomAccess(
            fileId = fileId,
            mediaId = chosenMediaId,
        ).getOrElse { err ->
            failSession(myGen, err.message ?: tr("无法打开音频流", "Failed to open audio stream", "音声ストリームを開けません", "오디오 스트림을 열 수 없습니다", "無法開啟音訊串流"))
            return null
        }
        if (generation != myGen) {
            runCatching { randomSource.close() }
            return null
        }

        val pcmReader = try {
            decoder!!.open(source = randomSource, audioStreamIndex = streamOrdinal)
        } catch (t: Throwable) {
            runCatching { randomSource.close() }
            throw t
        }
        if (generation != myGen) {
            runCatching { pcmReader.close() }
            runCatching { randomSource.close() }
            return null
        }

        val created = AudioContext(
            fileId = fileId,
            audioStreamIndex = streamOrdinal,
            effectiveGcid = effectiveGcid,
            chosenMediaId = chosenMediaId,
            nodeUrl = node.url,
            randomSource = randomSource,
            pcmReader = pcmReader,
            vadGate = SileroVadGate(vadFactory?.invoke()),
        )
        withPikoLock(contextLock) { audioContext = created }
        return created
    }

    private fun hasPendingSeek(): Boolean = withPikoLock(contextLock) { pendingSeekTargetMs != null }

    private fun pollCommittedSeek(playPosMs: Long, lastPlayPosMs: Long): Long? {
        val explicit = withPikoLock(contextLock) {
            val target = pendingSeekTargetMs ?: return@withPikoLock null
            val marked = pendingSeekMarkedAt ?: return@withPikoLock null
            if (marked.elapsedNow().inWholeMilliseconds >= SEEK_DEBOUNCE_MS) {
                pendingSeekTargetMs = null
                pendingSeekMarkedAt = null
                target
            } else {
                null
            }
        }
        if (explicit != null) return explicit
        // 兜底检测未经过 onSeek() 的外部跳播（如快捷键或系统媒体控制）
        val jumped = playPosMs < lastPlayPosMs - SEEK_BACK_TOLERANCE_MS ||
            playPosMs > lastPlayPosMs + SEEK_FORWARD_JUMP_MS
        return if (jumped && !hasPendingSeek()) playPosMs else null
    }

    private fun hasSpeechWithinLookahead(queue: ArrayDeque<TaggedAudioFrame>, fromMs: Long): Boolean {
        val horizon = fromMs + VAD_MERGE_GAP_MS
        for (frame in queue) {
            if (frame.positionMs > horizon) break
            if (frame.isSpeech) return true
        }
        return false
    }

    private fun failSession(myGen: Long, message: String) {
        if (generation != myGen) return
        releasePlaybackHold()
        _uiState.update {
            it.copy(
                on = false,
                status = AiSubtitleStatus.Error,
                errorMessage = message,
                activeCueText = null,
            )
        }
    }

    companion object {
        private const val AI_SUB_LOG_TAG = "AiSubtitle"
        private const val FRAME_MS = AiSubtitleAudioFormat.RECOMMENDED_FRAME_MS.toLong()
        private const val AI_LEAD_BASE_MS = AiSubtitleAudioFormat.RECOMMENDED_LEAD_MS
        private const val CATCHUP_FRAME_INTERVAL_MS = 33L      // 3x 追赶（已通过 subsrelay 实测验证）
        private const val HISTORY_MAX_FRAMES = 300             // 30 秒本地历史缓冲（~960 KB）
        private const val MAX_LOOKAHEAD_FRAMES = 220           // 最多超前缓存 22 秒
        private const val REPLAY_BACKTRACK_MS = 1_500L
        private const val PREROLL_FRAMES = 10                  // 1.0 s 语音前导缓冲，配合轻声慢通道确保句首完整
        private const val VAD_MERGE_GAP_MS = 10_000L           // 前瞻 10s 内有后续人声直接合并，避免对话间频繁 PAUSE/RESUME
        private const val PAUSE_OVERHEAD_MS = 5_000L           // 单次 PAUSE 服务端收尾扣费基准
        private const val SAVED_FLUSH_STEP_SEC = 5L
        private const val SILENCE_PAUSE_AFTER_MS = 8_000L      // 连续 8s 无对白才进入 PAUSE，杜绝短停顿抖动与 soniox_unavailable
        private const val SMART_HOLD_DEADLINE_MS = 6_000L      // WS 就绪后智能等待锁最大墙钟上限
        private const val SMART_HOLD_SCAN_MS = 3_000L          // 落点后 3s 均无声即刻 0 等待放行
        private const val SEEK_DEBOUNCE_MS = 200L
        private const val SEEK_BACK_TOLERANCE_MS = 600L
        private const val SEEK_FORWARD_JUMP_MS = 2_500L
        private const val PACE_TICK_MS = 30L
        private const val OVERLAY_TICK_MS = 100L
        private const val MAX_TRANSIENT_RETRIES = 3
        private val TRANSIENT_RETRY_BACKOFF_MS = longArrayOf(1_500L, 3_000L, 5_000L)
    }
}
