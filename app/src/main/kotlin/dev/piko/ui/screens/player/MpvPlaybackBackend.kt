package dev.piko.ui.screens.player

import android.content.Context
import android.net.Uri
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jdtech.mpv.MPVLib
import dev.jdtech.mpv.MPVLib.MpvEvent
import dev.jdtech.mpv.MPVLib.MpvFormat
import dev.piko.shared.log.LogLevel
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.player.ExternalSubtitle
import dev.piko.shared.media.player.MPV_SUBTITLE_LANGUAGES
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.shared.media.player.mpvSubtitleAddCommands
import dev.piko.shared.media.player.mpvSubtitleSelectCommand
import dev.piko.shared.media.player.readMpvTracks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * libmpv 的播放后端。
 *
 * 一个实例对应一个 mpv 句柄，互相独立，段落预览可以同时开两个。属性变化从 mpv 的
 * 事件线程回调进来，直接写 Compose State：快照系统允许跨线程写入，读取方照常重组。
 *
 * 画面挂接由 [MpvVideoSurface] 驱动：Surface 在，vo 才开；App 进后台 Surface 被销毁时
 * 先把 vo 换成 null 再解绑，回来后重新挂上并恢复 vo，与 mpv-android 的做法一致。
 *
 * [preview] 为 true 时是段落预览与随机片段：不带字幕、缓存小。[keyframeStart] 见 VideoPreviewSupport。
 * [headless] 为 true 时 vo=null，不需要 Surface 就开播，只解码不出画面，供没有可靠 GPU 的环境（模拟器冒烟）使用。
 */
internal class MpvPlaybackBackend(
    private val context: Context,
    private val preview: Boolean = false,
    private val keyframeStart: Boolean = false,
    private val headless: Boolean = false,
    /** 设置里的「硬件解码」，见 PikoUserPreferences.hardwareDecodingFlow。 */
    private val hardwareDecoding: Boolean = true,
) : PlaybackBackend, MPVLib.EventObserver, MPVLib.LogObserver {

    private val mpv: MPVLib = MPVLib.create(context) ?: error("libmpv 初始化失败")

    override var positionMillis by mutableLongStateOf(0L)
        private set
    override var durationMillis by mutableLongStateOf(0L)
        private set
    override var bufferedPositionMillis by mutableLongStateOf(0L)
        private set
    override var isPlaying by mutableStateOf(false)
        private set
    override val isBuffering: Boolean get() = isLoadingFile || isSeeking || isPausedForCache
    // 不能写成 private set 的属性：生成的 setter 与接口的 setSpeed/setAspectRatio 签名相撞
    private var currentSpeed by mutableFloatStateOf(1f)
    override val speed: Float get() = currentSpeed
    override val supportsSpeed: Boolean = true
    private var currentAspectRatio by mutableStateOf(PlayerAspectRatio.Fit)
    override val aspectRatio: PlayerAspectRatio get() = currentAspectRatio
    override var videoAspect by mutableStateOf<Float?>(null)
        private set
    private var currentVolume by mutableFloatStateOf(1f)
    override val volume: Float get() = currentVolume

    override var audioTracks by mutableStateOf<List<MediaTrack>>(emptyList())
        private set
    override var subtitleTracks by mutableStateOf<List<MediaTrack>>(emptyList())
        private set
    override var selectedAudioTrackId by mutableStateOf<String?>(null)
        private set
    override var selectedSubtitleTrackId by mutableStateOf<String?>(null)
        private set

    // 当前文件的外挂字幕，等 FILE_LOADED 再挂：loadfile 之前 sub-add 会挂到上一个文件上
    @Volatile private var pendingSubtitles: List<ExternalSubtitle> = emptyList()

    // 最近一次 loadfile 已到 FILE_LOADED。手动挂的字幕据此决定立即 sub-add 还是排进上面那一批
    @Volatile private var fileLoaded = false

    private var isLoadingFile by mutableStateOf(false)
    private var isSeeking by mutableStateOf(false)
    private var isPausedForCache by mutableStateOf(false)

    private val _events = MutableSharedFlow<PlaybackBackendEvent>(extraBufferCapacity = 16)
    override val events: Flow<PlaybackBackendEvent> = _events.asSharedFlow()

    // 以下字段只在主线程读写
    private var surfaceAttached = false
    private var attachedSurface: Surface? = null
    private var videoOutputDisabled = false
    private var pendingLoad: Array<String>? = null
    private var released = false
    private var observing = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var resizedSinceRedraw = false

    // 暂停时 mpv 不会因为换尺寸而重画，原地精确 seek 一次逼它按新尺寸再出一帧
    private val redrawGate = SurfaceRedrawGate { onMpv { mpv.command(arrayOf("seek", "0", "relative+exact")) } }

    // mpv 每个 loadfile 恰好对应一个 END_FILE，且按提交顺序到达。换片或停止前记下
    // 已提交的数量，此前的 END_FILE 都是我们自己替换掉的；超出部分才是当前文件异常结束。
    // keep-open=yes 时自然放完不发 END_FILE，只把 eof-reached 置真，所以这里不会误判
    @Volatile private var issuedLoads = 0
    @Volatile private var supersededLoads = 0
    @Volatile private var receivedEnds = 0

    @Volatile private var lastErrorLog: String? = null
    @Volatile private var readySent = false

    // 每个播放器只记一次实际用上的音频输出与硬解，核对 ao 的设定生效没有
    @Volatile private var loggedAudioOutput = false
    @Volatile private var rotateDegrees = 0L
    @Volatile private var rawAspect: Double? = null

    init {
        val configDir = MpvConfig.prepare(context)
        // 读取 config-dir 下的 fonts.conf 需要 config=yes；目录里没有 mpv.conf，不会读到别的配置
        mpv.setOptionString("config", "yes")
        mpv.setOptionString("config-dir", configDir.absolutePath)
        mpv.setOptionString("vo", if (headless) "null" else VIDEO_OUTPUT)
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        // 零拷贝的 mediacodec 走 AImageReader 与 vo=gpu 互通；不支持的编码（wmv3、rv40 等）
        // 自动落回软解，这正是换 mpv 的目的
        mpv.setOptionString("hwdec", if (hardwareDecoding) "mediacodec,mediacodec-copy" else "no")
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("idle", "yes")
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("osc", "no")
        mpv.setOptionString("ytdl", "no")
        mpv.setOptionString("load-scripts", "no")
        mpv.setOptionString("input-default-bindings", "no")
        mpv.setOptionString("input-vo-keyboard", "no")
        mpv.setOptionString("save-position-on-quit", "no")
        mpv.setOptionString("sub-font-provider", "fontconfig")
        mpv.setOptionString("slang", MPV_SUBTITLE_LANGUAGES)
        mpv.setOptionString("network-timeout", "30")
        // 代理背后的 SDK reader 已经预读 32 MiB，mpv 这层不必再囤太多
        mpv.setOptionString("cache", "yes")
        if (preview) {
            // 打开时少探测，尽量只读预取好的那几秒开头。FFmpeg 默认往后探到 5 MB，低码率的片段五秒不到两 MB，
            // 一探就读到冷的那截：冷门视频的 CDN 边缘没缓存，一段取好了开头，装进播放器却等了 10.9 秒（2026-09-28）
            mpv.setOptionString("demuxer-lavf-probesize", "$PREVIEW_PROBE_BYTES")
            mpv.setOptionString("demuxer-lavf-analyzeduration", "$PREVIEW_ANALYZE_SECONDS")
            mpv.setOptionString("sid", "no")
            if (keyframeStart) mpv.setOptionString("hr-seek", "no")
            // 字节上限对低码率的流不够：720P 转码约 120 KB/s，8 MiB 是一分多钟。预渲染的播放器暂停着
            // 也会读满它，把下一段的预取挤慢，所以再按时长卡一道
            mpv.setOptionString("cache-secs", "8")
            mpv.setOptionString("demuxer-max-bytes", "${8 * MIB}")
            mpv.setOptionString("demuxer-max-back-bytes", "${4 * MIB}")
        } else {
            mpv.setOptionString("demuxer-max-bytes", "${48 * MIB}")
            mpv.setOptionString("demuxer-max-back-bytes", "${16 * MIB}")
        }
        mpv.addObserver(this)
        mpv.addLogObserver(this)
        mpv.init()
    }

    /**
     * 属性订阅推迟到第一次 open，不放在构造函数里。
     *
     * 后端通常在 remember 里构造，此时处于组合的快照中，下面这些 Compose state 是在这个尚未提交的
     * 快照里创建的。订阅一注册，mpv 线程马上回报初始值（pause=false），写进的是全局快照；
     * 等组合快照提交，它那份新建时的初值更新，把 mpv 写的值盖掉。pause 的初始值只报这一次，
     * isPlaying 于是一直是 false，播放键点下去调的是 play()，看起来就是暂停不了。
     * open 在协程里调用，那时组合早已提交。
     */
    private fun observePropertiesOnce() {
        if (observing) return
        observing = true
        onMpv {
            mpv.observeProperty("time-pos", MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("duration", MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("demuxer-cache-time", MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("pause", MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("paused-for-cache", MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("eof-reached", MpvFormat.MPV_FORMAT_FLAG)
            mpv.observeProperty("speed", MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("volume", MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("video-params/aspect", MpvFormat.MPV_FORMAT_DOUBLE)
            mpv.observeProperty("video-params/rotate", MpvFormat.MPV_FORMAT_INT64)
            // 轨道增减（含 sub-add）改 count，切换改 aid、sid；任何一个变了都整份重读
            mpv.observeProperty("track-list/count", MpvFormat.MPV_FORMAT_INT64)
            mpv.observeProperty("aid", MpvFormat.MPV_FORMAT_STRING)
            mpv.observeProperty("sid", MpvFormat.MPV_FORMAT_STRING)
        }
    }

    override suspend fun open(
        target: PlaybackTarget,
        startMillis: Long,
        playWhenReady: Boolean,
        subtitles: List<ExternalSubtitle>,
    ) {
        if (released) return
        fileLoaded = false
        // 段落预览关着音轨与字幕，挂了也不显示
        pendingSubtitles = if (preview) emptyList() else subtitles
        val uri = when (target) {
            is PlaybackTarget.LocalFile -> localUri(target.path) ?: return
            is PlaybackTarget.Url -> target.url
        }
        observePropertiesOnce()
        val start = String.format(Locale.US, "%.3f", startMillis.coerceAtLeast(0L) / 1000.0)
        onMpv { mpv.setPropertyBoolean("pause", !playWhenReady) }
        positionMillis = startMillis.coerceAtLeast(0L)
        durationMillis = 0L
        bufferedPositionMillis = 0L
        // mpv 0.38 起 loadfile 的第三个参数是插入位置，逐文件选项放在第四个
        val command = arrayOf("loadfile", uri, "replace", "-1", "start=$start")
        if (surfaceAttached || headless) {
            issueLoad(command)
        } else {
            // 没有 Surface 时 vo 初始化失败，mpv 会把视频轨整条关掉只放声音，得等画面挂上
            pendingLoad = command
            isLoadingFile = true
        }
    }

    /**
     * SAF 目录里的文件是 content: URI，mpv 读不了。按 mpv-android 的做法由 ContentResolver
     * 打开描述符交给 mpv，fdclose:// 表示描述符归 mpv，播完由它关闭。打不开时返回 null。
     */
    private fun localUri(path: String): String? {
        if (!path.startsWith("content:")) return path
        val descriptor = runCatching { context.contentResolver.openFileDescriptor(Uri.parse(path), "r") }.getOrNull()
            ?: return null
        return "fdclose://${descriptor.detachFd()}"
    }

    override fun stop() {
        if (released) return
        pendingLoad = null
        supersededLoads = issuedLoads
        onMpv { mpv.command(arrayOf("stop")) }
        isLoadingFile = false
    }

    override fun play() {
        onMpv { mpv.setPropertyBoolean("pause", false) }
    }

    override fun pause() {
        onMpv { mpv.setPropertyBoolean("pause", true) }
    }

    override fun seekTo(positionMillis: Long) {
        if (released) return
        val seconds = String.format(Locale.US, "%.3f", positionMillis.coerceAtLeast(0L) / 1000.0)
        this.positionMillis = positionMillis.coerceAtLeast(0L)
        onMpv { mpv.command(arrayOf("seek", seconds, "absolute")) }
    }

    override fun setSpeed(speed: Float) {
        onMpv { mpv.setPropertyDouble("speed", speed.toDouble()) }
    }

    override fun setAspectRatio(mode: PlayerAspectRatio) {
        if (released) return
        onMpv {
            when (mode) {
                PlayerAspectRatio.Fit -> {
                    mpv.setPropertyString("keepaspect", "yes")
                    mpv.setPropertyDouble("panscan", 0.0)
                }

                PlayerAspectRatio.Crop -> {
                    mpv.setPropertyString("keepaspect", "yes")
                    mpv.setPropertyDouble("panscan", 1.0)
                }

                PlayerAspectRatio.Stretch -> {
                    mpv.setPropertyString("keepaspect", "no")
                    mpv.setPropertyDouble("panscan", 0.0)
                }
            }
        }
        currentAspectRatio = mode
    }

    override fun setVolume(volume: Float) {
        onMpv { mpv.setPropertyDouble("volume", volume.coerceIn(0f, 1f) * 100.0) }
    }

    /** 见 PreviewBackend.setBufferAhead。mpv 的缓存选项改了下一轮读即生效，不必重开文件。 */
    fun setBufferAhead(seconds: Int) {
        onMpv { mpv.setPropertyString("cache-secs", "$seconds") }
    }

    fun bufferReport(): String? =
        if (released) null else "cache-secs=${mpv.getPropertyString("cache-secs")}，缓存 ${mpv.getPropertyString("demuxer-cache-duration")} 秒"

    override fun selectAudioTrack(id: String) {
        onMpv { mpv.setPropertyString("aid", id) }
    }

    override fun selectSubtitleTrack(id: String?) {
        onMpv { mpv.setPropertyString("sid", id ?: "no") }
    }

    override val canAddSubtitle: Boolean get() = !preview

    override fun addSubtitle(subtitle: ExternalSubtitle) {
        if (released || preview) return
        // 文件还没加载好时 sub-add 会挂到上一个文件上或直接失败，排进 FILE_LOADED 时的那一批
        if (!fileLoaded) {
            pendingSubtitles = pendingSubtitles + subtitle
            return
        }
        onMpv { mpv.command(mpvSubtitleSelectCommand(subtitle)) }
    }

    override val supportsRotation: Boolean = true

    // video-rotate 会加进 video-params/rotate，画面比例经那里的观察自动换过来，这里不必另算
    override fun setRotation(degrees: Int) {
        onMpv { mpv.setPropertyString("video-rotate", "$degrees") }
    }

    private fun refreshTracks() {
        if (released) return
        val snapshot = readMpvTracks { mpv.getPropertyString(it) }
        audioTracks = snapshot.audio
        subtitleTracks = snapshot.subtitles
        selectedAudioTrackId = snapshot.selectedAudioId
        selectedSubtitleTrackId = snapshot.selectedSubtitleId
    }

    private fun attachPendingSubtitles() {
        val subtitles = pendingSubtitles
        pendingSubtitles = emptyList()
        if (subtitles.isEmpty() || released) return
        val hasSelected = readMpvTracks { mpv.getPropertyString(it) }.selectedSubtitleId != null
        mpvSubtitleAddCommands(subtitles, hasSelected).forEach { mpv.command(it) }
    }

    fun attachSurface(surface: Surface) {
        if (released) return
        attachedSurface = surface
        val restoreVideoOutput = videoOutputDisabled
        surfaceAttached = true
        videoOutputDisabled = false
        onMpv {
            mpv.attachSurface(surface)
            mpv.setOptionString("force-window", "yes")
            if (restoreVideoOutput) mpv.setPropertyString("vo", VIDEO_OUTPUT)
        }
        pendingLoad?.let {
            pendingLoad = null
            issueLoad(it)
        }
    }

    fun setSurfaceSize(width: Int, height: Int) {
        if (released || !surfaceAttached) return
        if (width != surfaceWidth || height != surfaceHeight) {
            surfaceWidth = width
            surfaceHeight = height
            resizedSinceRedraw = true
        }
        onMpv { mpv.setPropertyString("android-surface-size", "${width}x$height") }
    }

    /**
     * SurfaceView 要求重画时调用，[onDrawn] 在主线程上报「画完了」。只有尺寸真的变了才等 mpv 出新帧，
     * 见 [SurfaceRedrawGate]；其余的重画请求（刚创建、系统要求刷新）画面没有变形，立即放行。
     */
    fun afterRedraw(onDrawn: () -> Unit) {
        if (released || !surfaceAttached || !resizedSinceRedraw) {
            onDrawn()
            return
        }
        resizedSinceRedraw = false
        redrawGate.await(paused = !isPlaying, onDrawn = onDrawn)
    }

    /**
     * 让 mpv 放开画面。[afterDetached] 为 null 时等它做完才返回：SurfaceView 的销毁回调一返回画面就没了，
     * vo 还握着它就会崩。给了 [afterDetached] 就在 mpv 线程上放手之后调它，这里立即返回：
     * TextureView 可以先不释放，由调用方在回调里释放。
     */
    fun detachSurface(surface: Surface, afterDetached: (() -> Unit)? = null) {
        // 转屏或预览复用时，新 Surface 可能先挂上，旧视图随后才销毁。旧回调不能解绑新画面。
        if (attachedSurface !== surface) {
            if (afterDetached != null) calls.execute { afterDetached() }
            return
        }
        attachedSurface = null
        redrawGate.releaseAll()
        if (!surfaceAttached) {
            afterDetached?.invoke()
            return
        }
        surfaceAttached = false
        videoOutputDisabled = true
        val detach = Runnable {
            if (!destroyed) {
                // 换成 null 让 mpv 先放手，声音不受影响
                mpv.setPropertyString("vo", "null")
                mpv.setOptionString("force-window", "no")
                mpv.detachSurface()
            }
            afterDetached?.invoke()
        }
        if (afterDetached == null) calls.submit(detach).get() else calls.execute(detach)
    }

    /**
     * mpv 的写调用都在这条线程上按投递的先后执行，界面线程投递了就走。mpv 的属性读写要拿内核的锁，
     * 那个播放器正忙（开文件、拆建视频输出）时要等：手机上信息流快翻时，一次挂上或摘下画面在主线程上等
     * 80 到 300 毫秒，开文件、播放、改缓冲各几十毫秒，一翻页界面线程就被堵三四百毫秒（2026-09-28）。
     * 空闲一会儿线程自己退出，不必随播放器关掉。
     */
    private val calls = ThreadPoolExecutor(0, 1, CALL_THREAD_IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, "mpv-calls").apply { isDaemon = true }
    }

    // 只在 mpv 线程上读写：销毁之后排着的调用不再碰 mpv
    private var destroyed = false

    private fun onMpv(block: () -> Unit) {
        if (released) return
        calls.execute { if (!destroyed) block() }
    }

    /**
     * 销毁 mpv。mpv_terminate_destroy 会等播放线程退出，读网络时可能要一会儿，
     * 所以放到后台线程，不堵主线程。
     */
    fun release() {
        redrawGate.releaseAll()
        if (released) return
        released = true
        mpv.removeObserver(this)
        mpv.removeLogObserver(this)
        // 排在已投递的调用之后：它们还要用这个句柄
        calls.execute {
            destroyed = true
            mpv.destroy()
        }
    }

    /** 直接读 mpv 属性。播放冒烟用它确认解码出的画面尺寸、音轨与当前 vo。 */
    fun readStringProperty(name: String): String? = if (released) null else mpv.getPropertyString(name)

    fun readIntProperty(name: String): Int? = if (released) null else mpv.getPropertyInt(name)

    // 属性在当前文件上不存在时（例如没有音轨）当作读不到，不让面板因一项读失败整个出错
    override fun mpvProperty(name: String): String? = runCatching { readStringProperty(name) }.getOrNull()

    private fun issueLoad(command: Array<String>) {
        supersededLoads = issuedLoads
        issuedLoads += 1
        readySent = false
        lastErrorLog = null
        isLoadingFile = true
        onMpv { mpv.command(command) }
    }

    override fun eventProperty(property: String) {
        // 属性暂不可用（空闲、换片途中）
        when (property) {
            "duration" -> durationMillis = 0L
            "demuxer-cache-time" -> bufferedPositionMillis = 0L
            "video-params/aspect" -> {
                rawAspect = null
                videoAspect = null
            }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "video-params/rotate" -> {
                rotateDegrees = value
                updateVideoAspect()
            }
            "track-list/count" -> refreshTracks()
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> {
                redrawGate.frameShown()
                val millis = (value * 1000).toLong().coerceAtLeast(0L)
                // time-pos 每帧都报，控件只需要四分之一秒的精度，省掉多余的重组
                if (abs(millis - positionMillis) >= POSITION_GRANULARITY_MILLIS) positionMillis = millis
            }

            "duration" -> durationMillis = (value * 1000).toLong().coerceAtLeast(0L)
            "demuxer-cache-time" -> bufferedPositionMillis = (value * 1000).toLong().coerceAtLeast(0L)
            "speed" -> currentSpeed = value.toFloat()
            "volume" -> currentVolume = (value / 100).toFloat().coerceIn(0f, 1f)
            "video-params/aspect" -> {
                rawAspect = value
                updateVideoAspect()
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> isPlaying = !value
            "paused-for-cache" -> isPausedForCache = value
            "eof-reached" -> if (value) _events.tryEmit(PlaybackBackendEvent.Ended)
        }
    }

    override fun eventProperty(property: String, value: String) {
        if (property == "aid" || property == "sid") refreshTracks()
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MpvEvent.MPV_EVENT_START_FILE -> isLoadingFile = true
            MpvEvent.MPV_EVENT_FILE_LOADED -> {
                fileLoaded = true
                attachPendingSubtitles()
            }
            MpvEvent.MPV_EVENT_SEEK -> isSeeking = true
            MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                redrawGate.frameShown()
                isLoadingFile = false
                isSeeking = false
                if (!readySent) {
                    readySent = true
                    _events.tryEmit(PlaybackBackendEvent.Ready)
                    if (!loggedAudioOutput) {
                        loggedAudioOutput = true
                        PikoLog.d("Player", "mpv 音频输出：${mpv.getPropertyString("current-ao")}，硬解：${mpv.getPropertyString("hwdec-current")}")
                    }
                }
            }

            MpvEvent.MPV_EVENT_END_FILE -> {
                receivedEnds += 1
                if (receivedEnds > supersededLoads) {
                    isLoadingFile = false
                    isSeeking = false
                    _events.tryEmit(PlaybackBackendEvent.Error(lastErrorLog))
                }
            }
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        // 回调里是 v 级别的全量日志，只留错误作为失败原因
        if (level <= MPV_LOG_LEVEL_ERROR) lastErrorLog = "$prefix: ${text.trim()}"
        // 警告以上进应用日志。坏流会逐包刷同几句，最近记过的不再记，免得挤掉日志里别的内容。
        // 只比上一句不够：从切片开头解到第一个关键帧前，HEVC 解码器每个包交替报「PPS id out of range」
        // 与「Skipping invalid undecodable NALU」，一段就是几百行
        if (level <= MPV_LOG_LEVEL_WARN) {
            val line = "$prefix: ${text.trim()}"
            if (recentMpvLines.add(line)) {
                if (recentMpvLines.size > RECENT_MPV_LINES) recentMpvLines.remove(recentMpvLines.first())
                PikoLog.log(if (level <= MPV_LOG_LEVEL_ERROR) LogLevel.ERROR else LogLevel.WARN, "mpv", line, null)
            }
        }
    }

    // 只在 mpv 的日志线程上读写。按加入先后排，满了挤掉最早的
    private val recentMpvLines = LinkedHashSet<String>()

    private fun updateVideoAspect() {
        val aspect = rawAspect?.takeIf { it > 0.0 } ?: return
        videoAspect = if (rotateDegrees % 180L == 0L) aspect.toFloat() else (1.0 / aspect).toFloat()
    }

    private companion object {
        const val VIDEO_OUTPUT = "gpu"
        const val MIB = 1024 * 1024
        const val CALL_THREAD_IDLE_SECONDS = 5L
        const val PREVIEW_PROBE_BYTES = 1024 * 1024
        const val PREVIEW_ANALYZE_SECONDS = 1
        const val POSITION_GRANULARITY_MILLIS = 250L

        // mpv_log_level 的 MPV_LOG_LEVEL_ERROR。1.0.0 的构件没带 MpvLogLevel 常量类
        const val MPV_LOG_LEVEL_ERROR = 20
        const val MPV_LOG_LEVEL_WARN = 30

        // 够盖住一组交替刷的句子，又不至于把隔了很久再出现的同一个问题也吞掉
        const val RECENT_MPV_LINES = 16
    }
}

/**
 * mpv 的配置目录。
 *
 * libass 用 fontconfig 找字体，而预编译包里 fontconfig 的默认配置路径指向构建机，
 * 在设备上不存在，结果是一个字体也找不到、字幕整片空白。mpv 会把 config-dir 下的
 * fonts.conf 交给 libass，这里写一份指向系统字体目录的配置，中文靠系统自带的
 * Noto Sans CJK 覆盖，不必在 APK 里再塞一套几十 MB 的字体。
 */
internal object MpvConfig {
    fun prepare(context: Context): File {
        val dir = File(context.filesDir, "mpv").apply { mkdirs() }
        val cacheDir = File(context.cacheDir, "fontconfig").apply { mkdirs() }
        val fontsConf = File(dir, "fonts.conf")
        val content = fontsConfig(cacheDir)
        if (!fontsConf.exists() || fontsConf.readText() != content) fontsConf.writeText(content)
        return dir
    }

    private fun fontsConfig(cacheDir: File) = """
        <?xml version="1.0"?>
        <!DOCTYPE fontconfig SYSTEM "fonts.dtd">
        <fontconfig>
          <dir>/system/fonts</dir>
          <dir>/product/fonts</dir>
          <cachedir>${cacheDir.absolutePath}</cachedir>
          <alias>
            <family>sans-serif</family>
            <prefer>
              <family>Roboto</family>
              <family>Noto Sans CJK SC</family>
              <family>Noto Sans SC</family>
              <family>Droid Sans Fallback</family>
            </prefer>
          </alias>
        </fontconfig>
    """.trimIndent()
}
