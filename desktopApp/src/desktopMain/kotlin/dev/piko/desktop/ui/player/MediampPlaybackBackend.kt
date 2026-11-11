package dev.piko.desktop.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.piko.desktop.LinuxDesktop
import dev.piko.desktop.isLinux
import dev.piko.desktop.isMacOs
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.player.ExternalSubtitle
import dev.piko.shared.media.player.MPV_SUBTITLE_LANGUAGES
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.MpvTrackSnapshot
import dev.piko.shared.media.player.PlaybackFeedbackTracker
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.VideoCropRegion
import kotlin.math.ln
import dev.piko.shared.media.player.mpvSubtitleAddCommands
import dev.piko.shared.media.player.mpvSubtitleSelectCommand
import dev.piko.shared.media.player.readMpvTracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.openani.mediamp.MediampPlayer
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.features.AspectRatioMode
import org.openani.mediamp.features.AudioLevelController
import org.openani.mediamp.features.Buffering
import org.openani.mediamp.features.MediaMetadata
import org.openani.mediamp.features.PlaybackSpeed
import org.openani.mediamp.features.VideoAspectRatio
import org.openani.mediamp.isLoadingOrBuffering
import org.openani.mediamp.isMediaLoaded
import org.openani.mediamp.metadata.AudioTrack
import org.openani.mediamp.metadata.SubtitleTrack
import org.openani.mediamp.mpv.JvmMpvMediampPlayer
import org.openani.mediamp.mpv.EventListener
import org.openani.mediamp.mpv.MPVEvent
import org.openani.mediamp.mpv.MPVHandle
import org.openani.mediamp.source.UriMediaData
import java.io.File

/**
 * MediaMP（mpv 后端）到共用播放接口的转接。
 *
 * 能力按后端注册，取不到的特性在这里降级：倍速取不到就报 supportsSpeed = false，
 * 画面比例与音量取不到就报 null，控件据此隐藏入口。
 */
internal class MediampPlaybackBackend(
    val player: MediampPlayer,
    private val scope: CoroutineScope,
    private val preview: Boolean = false,
    // 见 VideoPreviewSupport.rememberPreviewBackend
    private val keyframeStart: Boolean = false,
    /** 设置里的「硬件解码」，见 PikoUserPreferences.hardwareDecodingFlow。 */
    private val hardwareDecoding: Boolean = true,
) : PlaybackBackend {
    private val bufferingFeature = player.features[Buffering.Key]
    private val speedFeature = player.features[PlaybackSpeed.Key]
    private val aspectRatioFeature = player.features[VideoAspectRatio.Key]
    private val audioFeature = player.features[AudioLevelController.Key]
    private val metadataFeature = player.features[MediaMetadata.Key]
    private val mpv: MPVHandle? = mpvHandleOf(player)
    private val nativeInstanceId: Long? = mpv?.let { handle ->
        runCatching {
            MPVHandle::class.java.methods.single { it.name.startsWith("getPtr") && it.parameterCount == 0 }.invoke(handle) as Long
        }.getOrNull()
    }

    private val feedback = PlaybackFeedbackTracker()
    override val playbackGeneration: Long get() = feedback.generation
    override val bufferSnapshot get() = feedback.snapshot
    private var transcodeProbeTightened = false

    override var positionMillis by mutableLongStateOf(0L)
        private set
    override var durationMillis by mutableLongStateOf(0L)
        private set
    override var bufferedPositionMillis by mutableLongStateOf(0L)
        private set
    override var isPlaying by mutableStateOf(false)
        private set
    private var playerBuffering by mutableStateOf(false)
    private var manualSeeking by mutableStateOf(false)
    private var manualSeekGen = 0
    override val isBuffering: Boolean get() = playerBuffering || manualSeeking || awaitingReady

    // 不能写成 private set 的属性：生成的 setter 与接口的 setSpeed/setAspectRatio 签名相撞
    private var currentSpeed by mutableFloatStateOf(speedFeature?.value ?: 1f)
    override val speed: Float get() = currentSpeed
    override val supportsSpeed: Boolean = speedFeature != null
    private var currentAspectRatio by mutableStateOf(
        aspectRatioFeature?.mode?.value?.toShared(),
    )
    override val aspectRatio: PlayerAspectRatio? get() = currentAspectRatio
    private var currentCropRegion by mutableStateOf<VideoCropRegion?>(null)
    override val cropRegion: VideoCropRegion? get() = currentCropRegion
    override var videoAspect by mutableStateOf<Float?>(null)
        private set

    // MediaMP 的音量以 1 为 mpv 的 100，上限是 2；共用接口不放大，只用到 1
    private var currentVolume by mutableStateOf(audioFeature?.volume?.value?.coerceIn(0f, 1f))
    override val volume: Float? get() = currentVolume

    override var audioTracks by mutableStateOf<List<MediaTrack>>(emptyList())
        private set
    override var subtitleTracks by mutableStateOf<List<MediaTrack>>(emptyList())
        private set
    override var selectedAudioTrackId by mutableStateOf<String?>(null)
        private set
    override var selectedSubtitleTrackId by mutableStateOf<String?>(null)
        private set

    // 当前文件的外挂字幕，Ready 之后再挂：MediaMP 打开文件时不看 MediaExtraFiles
    private var pendingSubtitles: List<ExternalSubtitle> = emptyList()

    // MediaMP 读到的轨道，只在取不到 mpv 句柄时用来兜底
    private var audioCandidates: List<AudioTrack> = emptyList()
    private var subtitleCandidates: List<SubtitleTrack> = emptyList()

    private val _events = MutableSharedFlow<PlaybackBackendEvent>(extraBufferCapacity = 16)
    override val events: Flow<PlaybackBackendEvent> = _events.asSharedFlow()

    @Volatile
    private var closing = false

    fun markClosing() {
        closing = true
        nativeInstanceId?.let(MpvLogBridge::markStopping)
        // 替换为无引用的空监听器而非恢复 MediaMP 原始监听器：外层协程取消后 mpv 原生排空阶段仍会回调
        // onPropertyChange，恢复原始未防护监听器会导致跨 JNI 抛异常刷出错误日志。
        runCatching { mpv?.setEventListener(NO_OP_EVENT_LISTENER) }
        awaitingReady = false
        subtitlesAttached = false
        manualSeeking = false
    }

    // 正常播放时在 scope 的调度器（Compose 主线程）上读写，关闭时由后台清理回调复位。
    private var awaitingReady by mutableStateOf(false)
    private var subtitlesAttached = false
    private var hasNativeEventHook = false

    init {
        installPlaybackEvents()
        scope.launch(Dispatchers.IO) {
            while (isActive && !closing) {
                val generation = playbackGeneration
                val seconds = mpv?.let { runCatching { it.getPropertyString("demuxer-cache-duration")?.toDoubleOrNull() }.getOrNull() }
                val sampledAt = kotlin.time.TimeSource.Monotonic.markNow()
                withContext(scope.coroutineContext.minusKey(Job)) {
                    if (!closing && generation == playbackGeneration) {
                        feedback.sample(generation, seconds, speed, sampledAt)
                        bufferedPositionMillis = seconds?.takeIf { it.isFinite() && it >= 0 }
                            ?.let { positionMillis + (it * 1000).toLong() } ?: 0L
                    }
                }
                delay(250L)
            }
        }
        mpv?.setPropertyString("slang", MPV_SUBTITLE_LANGUAGES)
        // libass 缺字时在 Windows 上经 DirectWrite 找回退字体，传的语言是空串，非中文系统上给汉字挑的多是日文字体：
        // 繁体字在里面有，「这」「们」这类简体字没有，回退失败后落到 sub-font。默认的 sans-serif 在 Windows 上也不含中文，
        // 按当前平台与语言偏好挑选自带简繁字体（见 SUBTITLE_FALLBACK_FONT）
        mpv?.setPropertyString("sub-font", SUBTITLE_FALLBACK_FONT)
        // 纯文本字幕（SRT/VTT）按实际视频画面高度缩放而非整个窗口高度，避免竖窄窗口下字幕暴涨并落入上下大黑边
        mpv?.setPropertyString("sub-scale-with-window", "no")
        mpv?.setPropertyString("sub-use-margins", "no")
        mpv?.setPropertyString("sub-font-size", "38")
        mpv?.setPropertyString("sub-border-size", "2")
        if (preview) {
            // MediaMP 让 mpv 往后囤 64 MiB，暂停时也照读不误。随机片段同时开着三个预览播放器，
            // 各自以前台身份往后读几百 MB，把账号的连接占满，其余请求首字节要等十几秒
            mpv?.setPropertyString("demuxer-max-bytes", "${8 * MIB}")
            mpv?.setPropertyString("demuxer-max-back-bytes", "${4 * MIB}")
            // 字节上限对低码率的流不够：720P 转码约 120 KB/s，8 MiB 是一分多钟，比随机片段的一整段还长。
            // 预渲染的两个播放器暂停着也会读满它，把下一段的预取挤慢，所以再按时长卡一道
            mpv?.setPropertyString("cache-secs", "$PREVIEW_CACHE_SECONDS")
            // 打开时少探测，尽量只读预取好的那几秒开头，理由见 Android 的 MpvPlaybackBackend
            mpv?.setPropertyString("demuxer-lavf-probesize", "$PREVIEW_PROBE_BYTES")
            mpv?.setPropertyString("demuxer-lavf-analyzeduration", "$PREVIEW_ANALYZE_SECONDS")
        }
        if (keyframeStart) mpv?.setPropertyString("hr-seek", "no")
        // 显式按用户偏好同步 mpv 的 hwdec（开=auto,auto-copy，优先零拷贝硬解、不支持零拷贝互操作时自动回退至硬解拷贝；关=no）
        mpv?.setPropertyString("hwdec", if (hardwareDecoding) "auto,auto-copy" else "no")
        mpv?.setPropertyString("hwdec-codecs", "all")
        scope.launch {
            player.currentPositionMillis.collect { pos ->
                positionMillis = pos
            }
        }
        metadataFeature?.let { feature ->
            // MediaMP 自己读的轨道丢了音轨语言与外挂标志，只拿它的变化当通知，列表从 mpv 重读
            feature.audioTracks?.let { group ->
                scope.launch { group.candidates.collect { audioCandidates = it; refreshTracks(feature) } }
                scope.launch { group.selected.collect { refreshTracks(feature) } }
            }
            feature.subtitleTracks?.let { group ->
                scope.launch { group.candidates.collect { subtitleCandidates = it; refreshTracks(feature) } }
                scope.launch { group.selected.collect { refreshTracks(feature) } }
            }
        }
        scope.launch {
            player.mediaProperties.collect { properties ->
                durationMillis = properties?.durationMillis ?: 0L
                val width = properties?.videoWidth ?: 0
                val height = properties?.videoHeight ?: 0
                videoAspect = if (width > 0 && height > 0) width.toFloat() / height else null
                if (currentAspectRatio == PlayerAspectRatio.RegionCrop) {
                    currentCropRegion?.let(::applyMpvCropRegion)
                }
            }
        }
        scope.launch {
            player.state.collect { state ->
                if (closing) return@collect
                isPlaying = state.playWhenReady
                playerBuffering = state.isLoadingOrBuffering
                if (awaitingReady && !subtitlesAttached && state.isMediaLoaded) {
                    subtitlesAttached = true
                    attachPendingSubtitles()
                }
                if (!hasNativeEventHook && awaitingReady && state.isMediaLoaded && !state.isLoadingOrBuffering) {
                    awaitingReady = false
                    manualSeeking = false
                    _events.emit(PlaybackBackendEvent.Ready)
                }
            }
        }
        // mediamp-mpv 的 bufferedPercentage 对应 cache-buffering-state（仅在卡顿重缓冲期间为 0..100，
        // 正常播放恒为 0），不能按总时长折算为时间轴上的 bufferedPositionMillis，否则会把缓冲余量误报为 0ms。
        speedFeature?.let { feature -> scope.launch { feature.valueFlow.collect { currentSpeed = it } } }
        aspectRatioFeature?.let { feature ->
            scope.launch {
                feature.mode.collect {
                    if (currentAspectRatio != PlayerAspectRatio.RegionCrop) {
                        currentAspectRatio = it.toShared()
                    }
                }
            }
        }
        audioFeature?.let { feature -> scope.launch { feature.volume.collect { currentVolume = it.coerceIn(0f, 1f) } } }
        scope.launch {
            player.events.collect { event ->
                when (event) {
                    is PlaybackEvent.ErrorOccurred -> _events.emit(PlaybackBackendEvent.Error(event.error.message))
                    is PlaybackEvent.MediaEnded -> _events.emit(PlaybackBackendEvent.Ended)
                    else -> Unit
                }
            }
        }
    }

    override suspend fun open(
        target: PlaybackTarget,
        startMillis: Long,
        playWhenReady: Boolean,
        subtitles: List<ExternalSubtitle>,
    ) {
        if (closing) return
        val uri = when (target) {
            // 经 Path 取 URI：File.toURI 给的是 file:/home/…，mpv 只认带 // 的 file://，否则当成文件名，打开即失败
            is PlaybackTarget.LocalFile -> File(target.path).toPath().toUri().toString()
            is PlaybackTarget.Url -> target.url
        }
        feedback.invalidate()
        awaitingReady = true
        nativeInstanceId?.let(MpvLogBridge::clearHandle)
        PikoLog.d("Player", "Opening playback session: instance=${nativeInstanceId?.toString(16)}, generation=$playbackGeneration, preview=$preview")
        subtitlesAttached = false
        manualSeeking = false
        manualSeekGen++
        pendingSubtitles = subtitles
        mpv?.setPropertyString("hwdec", if (hardwareDecoding) "auto,auto-copy" else "no")
        if (!preview) {
            val isMpegTsTranscode = target is PlaybackTarget.Url && target.isMpegTsTranscode
            if (isMpegTsTranscode) {
                mpv?.setPropertyString("demuxer-lavf-probesize", "$MPEGTS_TRANSCODE_PROBE_BYTES")
                mpv?.setPropertyString("demuxer-lavf-analyzeduration", "$MPEGTS_TRANSCODE_ANALYZE_SECONDS")
                transcodeProbeTightened = true
            } else if (transcodeProbeTightened) {
                // mpv 对 demuxer-lavf-probesize 要求写入整数 >= 32（读取未设置属性会返回 "0"，写回 "0" 会报错）
                mpv?.setPropertyString("demuxer-lavf-probesize", "$DEFAULT_LAVF_PROBE_BYTES")
                mpv?.setPropertyString("demuxer-lavf-analyzeduration", "0")
                transcodeProbeTightened = false
            }
        }
        // 本机回环代理由 SDK 内部负责重试与选路；关闭 FFmpeg 的 HTTP 自动重连，避免关窗或切集撤回代理会话后
        // FFmpeg 继续对已撤回的 127.0.0.1 URL 发重连请求并刷出 503/404 与残缺包报错。
        when {
            uri.startsWith("http://127.0.0.1:") ->
                mpv?.setPropertyString("stream-lavf-o", "reconnect=0,reconnect_streamed=0,reconnect_on_network_error=0")
            uri.startsWith("http://") || uri.startsWith("https://") ->
                mpv?.setPropertyString("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=2")
        }
        try {
            player.setMediaData(UriMediaData(uri), playWhenReady = playWhenReady, startPositionMillis = startMillis)
        } catch (e: Exception) {
            // 打开途中被取消时，MediaMP 让 mpv 卸载半开的文件，mpv 以加载失败收场，MediaMP 再把它当
            // PlaybackException 抛出来，而不是取消。取消中的协程以非取消异常结束会报给 UI 线程的异常处理，
            // 整个应用就崩了；随机片段换装播放器时常有这种取消
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    override fun selectAudioTrack(id: String) {
        if (closing) return
        val handle = mpv
        if (handle != null) {
            handle.setPropertyString("aid", id)
        } else {
            val track = audioCandidates.firstOrNull { it.internalId == id } ?: return
            metadataFeature?.audioTracks?.select(track)
        }
    }

    override fun selectSubtitleTrack(id: String?) {
        if (closing) return
        val handle = mpv
        if (handle != null) {
            handle.setPropertyString("sid", id ?: "no")
        } else {
            val group = metadataFeature?.subtitleTracks ?: return
            val track = id?.let { wanted -> subtitleCandidates.firstOrNull { it.internalId == wanted } ?: return }
            // 关字幕是 select(null)：MediaMP 的实现按 null 写 sid=no，只是接口的类型参数不可空
            @Suppress("UNCHECKED_CAST")
            (group as org.openani.mediamp.metadata.TrackGroup<SubtitleTrack?>).select(track)
        }
    }

    // 挂字幕、旋转都要直接对 mpv 下命令，MediaMP 0.5.0 的特性里没有这两样；句柄取不到就不给入口
    override val canAddSubtitle: Boolean get() = mpv != null

    /**
     * 开播之后照样能挂：自动的那几条本来就是在 Ready 之后 sub-add 的，不必为一条新字幕重开文件。
     * 还在打开时排进 Ready 那一批。
     */
    override fun addSubtitle(subtitle: ExternalSubtitle) {
        if (closing) return
        val handle = mpv ?: return
        if (awaitingReady) {
            pendingSubtitles = pendingSubtitles + subtitle
            return
        }
        handle.command(*mpvSubtitleSelectCommand(subtitle))
    }

    override val supportsRotation: Boolean get() = mpv != null

    /**
     * MediaMP 按 video-params 的变化重读 dwidth、dheight 作为 mediaProperties 的画面尺寸，mpv 给出的这两个值
     * 已按叠加后的旋转交换过宽高，[videoAspect] 于是跟着变，不必自己换算。
     */
    override fun setRotation(degrees: Int) {
        if (closing) return
        mpv?.setPropertyString("video-rotate", "$degrees")
    }

    private fun refreshTracks(feature: MediaMetadata) {
        if (closing) return
        val snapshot = mpv?.let { handle -> readMpvTracks { handle.getPropertyString(it) } }
            ?: snapshotOf(feature, audioCandidates, subtitleCandidates)
        audioTracks = snapshot.audio
        subtitleTracks = snapshot.subtitles
        selectedAudioTrackId = snapshot.selectedAudioId
        selectedSubtitleTrackId = snapshot.selectedSubtitleId
    }

    private fun attachPendingSubtitles() {
        if (closing) return
        val subtitles = pendingSubtitles
        pendingSubtitles = emptyList()
        val handle = mpv ?: return
        if (subtitles.isEmpty()) return
        val hasSelected = readMpvTracks { handle.getPropertyString(it) }.selectedSubtitleId != null
        mpvSubtitleAddCommands(subtitles, hasSelected).forEach { handle.command(*it) }
    }

    private fun installPlaybackEvents() {
        val handle = mpv ?: return
        runCatching {
            val field = MPVHandle::class.java.getDeclaredField("eventListener").apply { isAccessible = true }
            val delegate = field.get(handle) as EventListener
            handle.setEventListener(object : EventListener {
                override fun onPropertyChange(name: String) {
                    if (closing) return
                    runCatching { delegate.onPropertyChange(name) }
                }

                override fun onPropertyChange(name: String, value: Boolean) {
                    if (closing) return
                    runCatching { delegate.onPropertyChange(name, value) }
                }

                override fun onPropertyChange(name: String, value: Long) {
                    if (closing) return
                    runCatching { delegate.onPropertyChange(name, value) }
                }

                override fun onPropertyChange(name: String, value: Double) {
                    if (closing) return
                    runCatching { delegate.onPropertyChange(name, value) }
                }

                override fun onPropertyChange(name: String, value: String) {
                    if (closing) return
                    runCatching { delegate.onPropertyChange(name, value) }
                }

                override fun onStartFile(playlistEntryId: Long) {
                    if (closing) return
                    runCatching { delegate.onStartFile(playlistEntryId) }
                }

                override fun onEndFile(reason: Int, error: Int, playlistEntryId: Long) {
                    if (closing) return
                    runCatching { delegate.onEndFile(reason, error, playlistEntryId) }
                }

                override fun onEvent(event: Int) {
                    if (closing) return
                    val generation = playbackGeneration
                    runCatching { delegate.onEvent(event) }
                    if (event == MPVEvent.PLAYBACK_RESTART && !closing) {
                        val nativeSeeking = runCatching { handle.getPropertyString("seeking") == "yes" }.getOrDefault(true)
                        val decoder = if (!preview && awaitingReady) runCatching { handle.getPropertyString("hwdec-current") }.getOrNull() else null
                        scope.launch {
                            if (closing || nativeSeeking || !feedback.restarted(generation)) return@launch
                            manualSeeking = false
                            if (awaitingReady) {
                                awaitingReady = false
                                if (!subtitlesAttached) { subtitlesAttached = true; attachPendingSubtitles() }
                                decoder?.let { PikoLog.i("Player", "Active decoder: $it") }
                                _events.emit(PlaybackBackendEvent.Ready)
                            }
                        }
                    }
                }
            })
            hasNativeEventHook = true
        }.onFailure {
            PikoLog.w("Player", "Failed to hook MPVHandle eventListener; falling back to state-based ready detection", it)
        }
    }

    override fun stop() {
        if (closing) return
        feedback.invalidate()
        nativeInstanceId?.let(MpvLogBridge::markStopping)
        // 同步向 libmpv 下达 stop，先于后续 withdrawPlaybackTransport 关闭回环代理 reader，避免 FFmpeg 读到半截包报错
        runCatching { mpv?.command("stop") }
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            stopOnMain()
        } else {
            scope.launch {
                if (!closing) stopOnMain()
            }
        }
    }

    private fun stopOnMain() {
        awaitingReady = false
        subtitlesAttached = false
        manualSeeking = false
        manualSeekGen++
        player.stopPlayback()
    }

    override suspend fun beginStop() = withContext(scope.coroutineContext.minusKey(Job)) {
        stop()
    }

    override val requiresAsyncStopDrain: Boolean get() = true

    override suspend fun stopAndAwaitIdle() = withContext(scope.coroutineContext.minusKey(Job)) {
        if (closing) return@withContext
        stop()
        val handle = mpv
        val startedAt = kotlin.time.TimeSource.Monotonic.markNow()
        var loggedFailure = false
        while (true) {
            if (closing) return@withContext
            val state = player.state.value
            val stateIdle = !state.isMediaLoaded && !state.isLoadingOrBuffering
            val mpvIdle = if (handle == null) {
                stateIdle
            } else {
                runCatching { handle.getPropertyString("idle-active") }
                    .onFailure {
                        if (!loggedFailure) {
                            loggedFailure = true
                            PikoLog.w("Player", "Failed to query mpv idle-active while draining playback", it)
                        }
                    }
                    .getOrNull() == "yes"
            }
            if (mpvIdle && (stateIdle || startedAt.elapsedNow().inWholeMilliseconds >= 250L)) return@withContext
            if (startedAt.elapsedNow().inWholeMilliseconds >= STOP_IDLE_TIMEOUT_MILLIS) {
                PikoLog.w(
                    "Player",
                    "Timed out waiting for player idle state (mpvIdle=$mpvIdle, mediaLoaded=${state.isMediaLoaded}, buffering=${state.isLoadingOrBuffering})",
                )
                return@withContext
            }
            delay(15L)
        }
    }

    override fun play() {
        if (closing) return
        player.resume()
    }

    override fun pause() {
        if (closing) return
        player.pause()
    }

    override fun seekTo(positionMillis: Long) {
        if (closing) return
        val target = positionMillis.coerceAtLeast(0L)
        ++manualSeekGen
        feedback.invalidate()
        manualSeeking = true
        // 1. 信息流预览（keyframeStart = true）启用了 hr-seek=no 与 8 MiB 小队列，
        //    而 MediaMP 的 player.seekTo 硬编码下发 "absolute+exact" 会强行覆盖 hr-seek=no，
        //    追帧对齐音视频时极易撑爆 8 MiB 队列导致 seek 卡死；
        // 2. 流总长未知（durationMillis <= 0）时 MediaMP 还会把目标 clamp 到 0。
        // 这两类情形直接经底层 mpv 句柄下发定位命令。
        if (mpv != null && (keyframeStart || durationMillis <= 0L)) {
            val mode = if (keyframeStart) "absolute+keyframes" else "absolute"
            mpv.command("seek", "${target / 1000.0}", mode)
        } else {
            player.seekTo(target)
        }
    }

    override fun setSpeed(speed: Float) {
        if (closing) return
        speedFeature?.set(speed)
    }

    override fun setVolume(volume: Float) {
        if (closing) return
        val feature = audioFeature ?: return
        val clamped = volume.coerceIn(0f, 1f)
        feature.setVolume(clamped)
        // 读数经协程回报会慢一拍，连按方向键时下一次要在这次的值上累加，先行写入
        currentVolume = clamped
        // 静音时调音量，意图是要听见
        if (volume > 0f && feature.isMute.value) feature.setMute(false)
    }

    /** 见 PreviewBackend.setBufferAhead。mpv 的缓存选项改了下一轮读即生效，不必重开文件。 */
    fun setBufferAhead(seconds: Int) {
        if (closing) return
        mpv?.setPropertyString("cache-secs", "$seconds")
    }

    // 读不到时 MPVHandle 可能抛错而不是返回 null（属性在当前文件上不存在），统一当作读不到
    override fun mpvProperty(name: String): String? {
        if (closing) return null
        return mpv?.let { handle -> runCatching { handle.getPropertyString(name) }.getOrNull() }
    }

    fun bufferReport(): String? {
        if (closing) return null
        return mpv?.let { handle ->
            val cacheSecs = runCatching { handle.getPropertyString("cache-secs") }.getOrNull()
            val cacheDuration = runCatching { handle.getPropertyString("demuxer-cache-duration") }.getOrNull()
            "cache-secs=$cacheSecs, demuxer-cache=${cacheDuration}s"
        }
    }

    override fun setAspectRatio(mode: PlayerAspectRatio) {
        if (closing) return
        currentAspectRatio = mode
        when (mode) {
            PlayerAspectRatio.Fit -> {
                resetMpvCropZoom()
                aspectRatioFeature?.setMode(AspectRatioMode.FIT)
            }
            PlayerAspectRatio.Crop -> {
                resetMpvCropZoom()
                aspectRatioFeature?.setMode(AspectRatioMode.CROP)
            }
            PlayerAspectRatio.Stretch -> {
                resetMpvCropZoom()
                aspectRatioFeature?.setMode(AspectRatioMode.STRETCH)
            }
            PlayerAspectRatio.RegionCrop -> {
                applyMpvCropRegion(currentCropRegion ?: VideoCropRegion().also { currentCropRegion = it })
            }
        }
    }

    override fun setCropRegion(region: VideoCropRegion?) {
        if (closing) return
        currentCropRegion = region
        if (region != null) {
            currentAspectRatio = PlayerAspectRatio.RegionCrop
            applyMpvCropRegion(region)
        } else if (currentAspectRatio == PlayerAspectRatio.RegionCrop) {
            setAspectRatio(PlayerAspectRatio.Fit)
        } else {
            resetMpvCropZoom()
        }
    }

    private fun applyMpvCropRegion(region: VideoCropRegion) {
        if (closing) return
        aspectRatioFeature?.setMode(AspectRatioMode.FIT)
        val handle = mpv ?: return
        val vAspect = videoAspect?.takeIf { it.isFinite() && it > 0f } ?: (16f / 9f)
        val cAspect = region.safeContainerAspect.takeIf { it > 0f } ?: vAspect
        val fitW = if (cAspect > vAspect) vAspect / cAspect else 1f
        val fitH = if (cAspect > vAspect) 1f else cAspect / vAspect
        val scale = maxOf(1f / (fitW * region.width), 1f / (fitH * region.height))
        val zoom = ln(scale.toDouble()) / ln(2.0)
        val panX = (0.5f - region.centerX).toDouble()
        val panY = (0.5f - region.centerY).toDouble()
        handle.setPropertyString("keepaspect", "yes")
        handle.setPropertyString("panscan", "0.0")
        handle.setPropertyString("video-zoom", zoom.toString())
        handle.setPropertyString("video-pan-x", panX.toString())
        handle.setPropertyString("video-pan-y", panY.toString())
    }

    private fun resetMpvCropZoom() {
        if (closing) return
        val handle = mpv ?: return
        handle.setPropertyString("video-zoom", "0.0")
        handle.setPropertyString("video-pan-x", "0.0")
        handle.setPropertyString("video-pan-y", "0.0")
    }
}

private fun AspectRatioMode.toShared(): PlayerAspectRatio = when (this) {
    AspectRatioMode.FIT -> PlayerAspectRatio.Fit
    AspectRatioMode.CROP -> PlayerAspectRatio.Crop
    AspectRatioMode.STRETCH -> PlayerAspectRatio.Stretch
}

/**
 * MediaMP 的 mpv 句柄。它把句柄的 getter 标成了 internal，而 0.5.0 打开文件时不理会 MediaExtraFiles，
 * 首帧 PLAYBACK_RESTART 就绪事件、外挂字幕、画面旋转与区域裁剪都依赖直调 mpv，所以经反射取。
 * 版本锁在 0.5.0（原因见 desktopApp/AGENTS.md），升级时这里要重新核对。
 * 取不到时为 null：退回基于 MediaMP 状态的首帧就绪判定与内嵌轨道读取，无外挂字幕、旋转与裁剪。
 */
private fun mpvHandleOf(player: MediampPlayer): MPVHandle? = runCatching {
    JvmMpvMediampPlayer::class.java.getMethod("getHandle" + "$" + "mediamp_mpv").invoke(player) as? MPVHandle
}.onFailure {
    PikoLog.w("Player", "Failed to reflect MPVHandle from Mediamp player; native event hook, rotation, crop, and external subtitles unavailable", it)
}.getOrNull()

// macOS 的 libass 经 CoreText 找字体，苹方简繁都全，系统自带。Linux 的 libass 经 fontconfig 找，
// 取界面用的那个中文字体，都没装时交给 fontconfig 按 sans-serif 挑
private val SUBTITLE_FALLBACK_FONT: String
    get() {
        val preferTraditional = dev.piko.shared.i18n.PikoLocale.effectiveLanguage == dev.piko.data.auth.AppLanguage.ZH_HANT ||
            (dev.piko.shared.i18n.PikoLocale.effectiveLanguage == dev.piko.data.auth.AppLanguage.EN &&
                dev.piko.shared.i18n.PikoLocale.systemLanguage == dev.piko.data.auth.AppLanguage.ZH_HANT)
        return when {
            isMacOs -> if (preferTraditional) "PingFang TC" else "PingFang SC"
            isLinux -> LinuxDesktop.cjkFontFamily ?: "sans-serif"
            else -> if (preferTraditional) "Microsoft JhengHei" else "Microsoft YaHei"
        }
    }

private const val MIB = 1024 * 1024

/** 预览往后缓冲的秒数，见 init。 */
private const val PREVIEW_CACHE_SECONDS = 8
private const val PREVIEW_PROBE_BYTES = 1024 * 1024
private const val PREVIEW_ANALYZE_SECONDS = 1
private const val DEFAULT_LAVF_PROBE_BYTES = 5_000_000
private const val MPEGTS_TRANSCODE_PROBE_BYTES = 512 * 1024
private const val MPEGTS_TRANSCODE_ANALYZE_SECONDS = 1
private const val STOP_IDLE_TIMEOUT_MILLIS = 2_000L

private val NO_OP_EVENT_LISTENER = object : EventListener {
    override fun onPropertyChange(name: String) = Unit
    override fun onPropertyChange(name: String, value: Boolean) = Unit
    override fun onPropertyChange(name: String, value: Long) = Unit
    override fun onPropertyChange(name: String, value: Double) = Unit
    override fun onPropertyChange(name: String, value: String) = Unit
    override fun onStartFile(playlistEntryId: Long) = Unit
    override fun onEndFile(reason: Int, mpvError: Int, playlistEntryId: Long) = Unit
    override fun onEvent(event: Int) = Unit
}

/** 句柄取不到时退回 MediaMP 读的轨道：音轨没有语言，也分不出外挂。 */
private fun snapshotOf(feature: MediaMetadata, audio: List<AudioTrack>, subtitles: List<SubtitleTrack>) = MpvTrackSnapshot(
    audio = audio.map { MediaTrack(it.internalId, it.name, null) },
    subtitles = subtitles.map { MediaTrack(it.internalId, it.labels.firstOrNull()?.value, it.language) },
    selectedAudioId = feature.audioTracks?.selected?.value?.internalId,
    selectedSubtitleId = feature.subtitleTracks?.selected?.value?.internalId,
)
