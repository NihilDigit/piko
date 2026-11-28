@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.piko.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.IosAvPlayerMediaBridge
import dev.piko.shared.media.WrappedAvPlayerUrl
import dev.piko.shared.media.player.ExternalSubtitle
import dev.piko.shared.media.player.MediaTrack
import dev.piko.shared.media.player.PlaybackBackend
import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.media.player.PlaybackFeedbackTracker
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.shared.media.player.PlayerAspectRatio
import io.github.nihildigit.pikpak.PlaybackBufferSnapshot
import kotlinx.cinterop.readValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVLayerVideoGravity
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaCharacteristicAudible
import platform.AVFoundation.AVMediaCharacteristicLegible
import platform.AVFoundation.AVMediaSelectionGroup
import platform.AVFoundation.AVMediaSelectionOption
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerActionAtItemEndPause
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeErrorKey
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerStatusFailed
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.asset
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentMediaSelection
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.isPlaybackLikelyToKeepUp
import platform.AVFoundation.loadedTimeRanges
import platform.AVFoundation.mediaSelectionGroupForMediaCharacteristic
import platform.AVFoundation.muted
import platform.AVFoundation.naturalSize
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.preferredForwardBufferDuration
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.rate
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.selectMediaOption
import platform.AVFoundation.status
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.tracksWithMediaType
import platform.AVFoundation.volume
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformIdentity
import platform.CoreGraphics.CGAffineTransformMakeRotation
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.CoreMedia.kCMTimePositiveInfinity
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSLocale
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSURL
import platform.Foundation.NSValue
import platform.Foundation.languageCode
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.TimeSource

/**
 * iOS 完整内置播放后端：基于原生 `AVPlayer` + 持久持有的 `AVPlayerLayer` + `IosAvPlayerMediaBridge`。
 *
 * 与信息流预览后端相比，额外支持：
 * - 真实时长透传至 HLS TS 桥接与就绪后延迟定位；
 * - 内封音轨与内封字幕轨枚举及实时切换；
 * - 顺时针 0/90/180/270 画面旋转（叠加在容器变换层并同步修正 [videoAspect]）；
 * - 听视频模式切换与持久 `AVPlayerLayer` 复用，避免进出画中画时重建图层导致 PiP 失效。
 */
internal class IosPlaybackBackend(
    private val durationHintSecondsProvider: () -> Long = { DEFAULT_DURATION_SECONDS },
) : PlaybackBackend {

    val player: AVPlayer = AVPlayer().apply {
        actionAtItemEnd = AVPlayerActionAtItemEndPause
        volume = 1f
        muted = false
    }

    /** 持久持有的渲染图层，供 `UIKitView` 宿主与 `AVPictureInPictureController` 稳定绑定。 */
    val playerLayer: AVPlayerLayer = AVPlayerLayer.playerLayerWithPlayer(player).apply {
        videoGravity = AVLayerVideoGravityResizeAspect
        backgroundColor = UIColor.blackColor.CGColor
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val feedback = PlaybackFeedbackTracker()

    override var playbackGeneration by mutableLongStateOf(0L)
        private set
    override var positionMillis by mutableLongStateOf(0L)
        private set
    override var durationMillis by mutableLongStateOf(0L)
        private set
    override var bufferedPositionMillis by mutableLongStateOf(0L)
        private set
    override var isPlaying by mutableStateOf(false)
        private set
    override var isBuffering by mutableStateOf(false)
        private set

    private var currentSpeed by mutableFloatStateOf(1f)
    override val speed: Float get() = currentSpeed
    override val supportsSpeed: Boolean get() = true

    private var currentAspectRatio by mutableStateOf(PlayerAspectRatio.Fit)
    override val aspectRatio: PlayerAspectRatio get() = currentAspectRatio

    private var rawVideoAspect: Float? = null
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

    override val supportsRotation: Boolean get() = true
    private var currentRotationDegrees by mutableIntStateOf(0)
    val rotationDegrees: Int get() = currentRotationDegrees

    private var audioOnlyMode by mutableStateOf(false)

    private var audioSelectionGroup: AVMediaSelectionGroup? = null
    private var subtitleSelectionGroup: AVMediaSelectionGroup? = null
    private var audioOptionsById: Map<String, AVMediaSelectionOption> = emptyMap()
    private var subtitleOptionsById: Map<String, AVMediaSelectionOption> = emptyMap()

    override val bufferSnapshot: PlaybackBufferSnapshot?
        get() = feedback.snapshot

    private val _events = MutableSharedFlow<PlaybackBackendEvent>(extraBufferCapacity = 16)
    override val events: Flow<PlaybackBackendEvent> = _events.asSharedFlow()

    private var openTicket = 0L
    private var released = false
    private var desiredPlaying = false
    private var isLoadingItem = false
    private var isSeeking = false
    private var readyEmitted = false
    private var failureEmitted = false
    private var tracksLoaded = false
    private var pendingStartMillis = 0L
    private var lastPlayCommandMark: TimeSource.Monotonic.ValueTimeMark? = null

    private var currentItem: AVPlayerItem? = null
    private var wrappedUrl: WrappedAvPlayerUrl? = null
    private var pollJob: Job? = null
    private var timeObserver: Any? = null
    private val itemObservers = mutableListOf<NSObjectProtocol>()

    init {
        val interval = CMTimeMakeWithSeconds(PERIODIC_INTERVAL_SECONDS, PREFERRED_TIMESCALE)
        timeObserver = player.addPeriodicTimeObserverForInterval(
            interval = interval,
            queue = dispatch_get_main_queue(),
        ) { _ ->
            if (!released) {
                syncItemMetrics()
                updateBufferingState()
            }
        }
    }

    fun currentVideoGravity(): AVLayerVideoGravity = when (currentAspectRatio) {
        PlayerAspectRatio.Fit, PlayerAspectRatio.RegionCrop -> AVLayerVideoGravityResizeAspect
        PlayerAspectRatio.Crop -> AVLayerVideoGravityResizeAspectFill
        PlayerAspectRatio.Stretch -> AVLayerVideoGravityResize
    }

    fun setAudioOnlyMode(enabled: Boolean) {
        onMain {
            if (released || audioOnlyMode == enabled) return@onMain
            audioOnlyMode = enabled
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            playerLayer.hidden = enabled
            CATransaction.commit()
        }
    }

    override suspend fun open(
        target: PlaybackTarget,
        startMillis: Long,
        playWhenReady: Boolean,
        subtitles: List<ExternalSubtitle>,
    ) {
        val ticket = withContext(Dispatchers.Main.immediate) {
            if (released) return@withContext -1L
            val next = ++openTicket
            detachCurrentItemLocked()
            desiredPlaying = playWhenReady
            isPlaying = playWhenReady
            isLoadingItem = true
            isBuffering = true
            pendingStartMillis = startMillis.coerceAtLeast(0L)
            positionMillis = pendingStartMillis
            playbackGeneration += 1L
            feedback.restarted(playbackGeneration)
            next
        }
        if (ticket < 0L) return

        val durationHint = durationHintSecondsProvider().takeIf { it > 0L } ?: DEFAULT_DURATION_SECONDS
        var bridgeHandle: WrappedAvPlayerUrl? = null
        val nsUrl: NSURL? = try {
            when (target) {
                is PlaybackTarget.LocalFile -> {
                    bridgeHandle = IosAvPlayerMediaBridge.Shared.wrapLocalTsFile(
                        filePath = target.path,
                        durationSeconds = durationHint,
                    )
                    if (bridgeHandle != null) {
                        NSURL.URLWithString(bridgeHandle.playbackUrl)
                    } else {
                        NSURL.fileURLWithPath(target.path)
                    }
                }
                is PlaybackTarget.Url -> {
                    bridgeHandle = IosAvPlayerMediaBridge.Shared.wrap(
                        url = target.url,
                        durationSeconds = durationHint,
                        forceHlsTs = target.isMpegTsTranscode,
                    )
                    NSURL.URLWithString(bridgeHandle.playbackUrl)
                }
            }
        } catch (e: CancellationException) {
            bridgeHandle?.close()
            throw e
        } catch (t: Throwable) {
            bridgeHandle?.close()
            withContext(Dispatchers.Main.immediate) {
                if (!released && ticket == openTicket) {
                    isLoadingItem = false
                    isBuffering = false
                    failureEmitted = true
                    _events.tryEmit(PlaybackBackendEvent.Error(t.message ?: "Failed to prepare iOS playback URL"))
                }
            }
            return
        }

        withContext(Dispatchers.Main.immediate) {
            if (released || ticket != openTicket) {
                bridgeHandle?.close()
                return@withContext
            }
            if (nsUrl == null) {
                bridgeHandle?.close()
                isLoadingItem = false
                isBuffering = false
                failureEmitted = true
                _events.tryEmit(PlaybackBackendEvent.Error("Invalid media URL"))
                return@withContext
            }

            IosHostBridge.configurePlaybackAudioSession(TAG)
            val asset = AVURLAsset.URLAssetWithURL(nsUrl, options = null)
            val item = AVPlayerItem.playerItemWithAsset(asset).apply {
                preferredForwardBufferDuration = FORWARD_BUFFER_SECONDS
            }
            wrappedUrl = bridgeHandle
            currentItem = item
            registerItemObservers(item, ticket)

            player.volume = currentVolume
            player.muted = currentVolume <= 0f
            player.replaceCurrentItemWithPlayerItem(item)
            if (desiredPlaying && pendingStartMillis <= 0L) {
                applyPlaybackRate()
            } else {
                player.pause()
            }
            startMonitorLoop(item, ticket)
        }
    }

    private fun startMonitorLoop(item: AVPlayerItem, ticket: Long) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && !released && ticket == openTicket && currentItem === item) {
                if (player.status == AVPlayerStatusFailed && !failureEmitted) {
                    handleItemFailure(player.error)
                    break
                }
                when (item.status) {
                    AVPlayerItemStatusFailed -> {
                        if (!failureEmitted) handleItemFailure(item.error)
                        break
                    }
                    AVPlayerItemStatusReadyToPlay -> {
                        onItemReady(item, ticket)
                    }
                }
                syncItemMetrics()
                updateBufferingState()
                delay(if (readyEmitted) STEADY_POLL_MS else READY_POLL_MS)
            }
        }
    }

    private fun onItemReady(item: AVPlayerItem, ticket: Long) {
        if (!tracksLoaded) {
            tracksLoaded = true
            refreshMediaTracks(item)
        }
        if (readyEmitted) return
        readyEmitted = true
        isLoadingItem = false
        syncItemMetrics()
        val seekTarget = pendingStartMillis
        pendingStartMillis = 0L
        if (seekTarget > 0L) {
            isSeeking = true
            val cmTarget = CMTimeMake(value = seekTarget, timescale = 1_000)
            player.seekToTime(
                cmTarget,
                toleranceBefore = kCMTimeZero.readValue(),
                toleranceAfter = kCMTimeZero.readValue(),
            ) { _ ->
                onMain {
                    if (released || ticket != openTicket || currentItem !== item) return@onMain
                    isSeeking = false
                    syncItemMetrics()
                    if (desiredPlaying) applyPlaybackRate() else player.pause()
                    updateBufferingState()
                    _events.tryEmit(PlaybackBackendEvent.Ready)
                }
            }
        } else {
            if (desiredPlaying) applyPlaybackRate() else player.pause()
            updateBufferingState()
            _events.tryEmit(PlaybackBackendEvent.Ready)
        }
    }

    private fun refreshMediaTracks(item: AVPlayerItem) {
        val asset = item.asset
        val audibleGroup = asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicAudible)
        val legibleGroup = asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible)
        audioSelectionGroup = audibleGroup
        subtitleSelectionGroup = legibleGroup

        val selection = item.currentMediaSelection
        val selectedAudio = audibleGroup?.let { selection.selectedMediaOptionInMediaSelectionGroup(it) }
        val selectedSub = legibleGroup?.let { selection.selectedMediaOptionInMediaSelectionGroup(it) }

        val nextAudioMap = LinkedHashMap<String, AVMediaSelectionOption>()
        val nextAudioList = audibleGroup?.options
            ?.filterIsInstance<AVMediaSelectionOption>()
            ?.mapIndexed { idx, opt ->
                val id = "a_$idx"
                nextAudioMap[id] = opt
                MediaTrack(
                    id = id,
                    title = opt.displayName,
                    language = opt.locale?.languageCode,
                )
            }
            .orEmpty()
        audioOptionsById = nextAudioMap
        audioTracks = nextAudioList
        selectedAudioTrackId = nextAudioMap.entries.firstOrNull { it.value == selectedAudio }?.key
            ?: nextAudioList.firstOrNull()?.id

        val nextSubMap = LinkedHashMap<String, AVMediaSelectionOption>()
        val nextSubList = legibleGroup?.options
            ?.filterIsInstance<AVMediaSelectionOption>()
            ?.mapIndexed { idx, opt ->
                val id = "s_$idx"
                nextSubMap[id] = opt
                MediaTrack(
                    id = id,
                    title = opt.displayName,
                    language = opt.locale?.languageCode,
                )
            }
            .orEmpty()
        subtitleOptionsById = nextSubMap
        subtitleTracks = nextSubList
        selectedSubtitleTrackId = nextSubMap.entries.firstOrNull { it.value == selectedSub }?.key
    }

    override fun selectAudioTrack(id: String) {
        onMain {
            if (released) return@onMain
            val item = currentItem ?: return@onMain
            val group = audioSelectionGroup ?: return@onMain
            val option = audioOptionsById[id] ?: return@onMain
            item.selectMediaOption(option, inMediaSelectionGroup = group)
            selectedAudioTrackId = id
        }
    }

    override fun selectSubtitleTrack(id: String?) {
        onMain {
            if (released) return@onMain
            val item = currentItem ?: return@onMain
            val group = subtitleSelectionGroup ?: return@onMain
            if (id == null) {
                item.selectMediaOption(null, inMediaSelectionGroup = group)
                selectedSubtitleTrackId = null
            } else {
                val option = subtitleOptionsById[id] ?: return@onMain
                item.selectMediaOption(option, inMediaSelectionGroup = group)
                selectedSubtitleTrackId = id
            }
        }
    }

    private fun registerItemObservers(item: AVPlayerItem, ticket: Long) {
        val center = NSNotificationCenter.defaultCenter
        val mainQueue = NSOperationQueue.mainQueue
        itemObservers += center.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = mainQueue,
        ) { _ ->
            if (released || ticket != openTicket || currentItem !== item) return@addObserverForName
            desiredPlaying = false
            isPlaying = false
            isBuffering = false
            syncItemMetrics()
            _events.tryEmit(PlaybackBackendEvent.Ended)
        }
        itemObservers += center.addObserverForName(
            name = AVPlayerItemFailedToPlayToEndTimeNotification,
            `object` = item,
            queue = mainQueue,
        ) { notification ->
            if (released || ticket != openTicket || currentItem !== item || failureEmitted) return@addObserverForName
            val nsError = notification?.userInfo?.get(AVPlayerItemFailedToPlayToEndTimeErrorKey) as? NSError
            handleItemFailure(nsError ?: item.error)
        }
        itemObservers += center.addObserverForName(
            name = AVPlayerItemPlaybackStalledNotification,
            `object` = item,
            queue = mainQueue,
        ) { _ ->
            if (released || ticket != openTicket || currentItem !== item) return@addObserverForName
            updateBufferingState()
        }
    }

    private fun handleItemFailure(error: NSError?) {
        failureEmitted = true
        isLoadingItem = false
        isBuffering = false
        desiredPlaying = false
        isPlaying = false
        val detail = error?.let { "${it.domain} (${it.code}): ${it.localizedDescription}" } ?: "AVPlayerItem failed"
        PikoLog.w(TAG, "iOS playback failed: $detail")
        _events.tryEmit(PlaybackBackendEvent.Error(detail))
    }

    private fun syncItemMetrics() {
        val item = currentItem ?: return
        val durSec = CMTimeGetSeconds(item.duration)
        if (durSec.isFinite() && durSec > 0.0) {
            durationMillis = (durSec * 1_000.0).roundToLong()
        }
        val posSec = CMTimeGetSeconds(item.currentTime())
        if (!isSeeking && posSec.isFinite() && posSec >= 0.0) {
            val posMs = (posSec * 1_000.0).roundToLong()
            positionMillis = if (durationMillis > 0L) posMs.coerceAtMost(durationMillis) else posMs
        }
        val loadedEndSec = loadedRangeEndSeconds(item)
        if (loadedEndSec != null) {
            val bufMs = (loadedEndSec * 1_000.0).roundToLong()
            bufferedPositionMillis = if (durationMillis > 0L) bufMs.coerceAtMost(durationMillis) else bufMs
        }
        if (rawVideoAspect == null) {
            resolveVideoAspect(item)?.let { resolved ->
                rawVideoAspect = resolved
                updateEffectiveVideoAspect()
            }
        }
        // 系统 PiP / 锁屏控制中心直接暂停或恢复 AVPlayer 时，同步回 Compose 播放态
        val inPlayGrace = lastPlayCommandMark?.let { it.elapsedNow().inWholeMilliseconds < PLAY_COMMAND_GRACE_MS } == true
        if (readyEmitted && !isSeeking && !inPlayGrace) {
            when (player.timeControlStatus) {
                AVPlayerTimeControlStatusPlaying -> {
                    desiredPlaying = true
                    isPlaying = true
                }
                AVPlayerTimeControlStatusPaused -> {
                    desiredPlaying = false
                    isPlaying = false
                }
            }
        }
        val aheadSec = if (loadedEndSec != null && posSec.isFinite() && posSec >= 0.0) {
            (loadedEndSec - posSec).coerceAtLeast(0.0)
        } else {
            null
        }
        val stalled = desiredPlaying &&
            player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate &&
            !item.isPlaybackLikelyToKeepUp()
        feedback.record(
            generation = playbackGeneration,
            aheadSeconds = aheadSec,
            pausedForCache = stalled,
            positionMillis = positionMillis,
            durationMillis = durationMillis,
        )
    }

    private fun updateBufferingState() {
        val item = currentItem
        isBuffering = when {
            item == null -> false
            isLoadingItem || isSeeking -> true
            !desiredPlaying -> false
            else -> player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        }
    }

    private fun resolveVideoAspect(item: AVPlayerItem): Float? {
        val track = item.asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? platform.AVFoundation.AVAssetTrack
            ?: return null
        val (w, h) = track.naturalSize.useContents { width to height }
        if (!w.isFinite() || !h.isFinite() || w <= 0.0 || h <= 0.0) return null
        val (txW, txH) = track.preferredTransform.useContents {
            val outW = abs(w * a + h * c)
            val outH = abs(w * b + h * d)
            outW to outH
        }
        val finalW = if (txW > 0.0) txW else w
        val finalH = if (txH > 0.0) txH else h
        return (finalW / finalH).toFloat().takeIf { it.isFinite() && it > 0.05f && it < 20f }
    }

    private fun updateEffectiveVideoAspect() {
        val base = rawVideoAspect ?: run {
            videoAspect = null
            return
        }
        videoAspect = if (currentRotationDegrees % 180 != 0) 1f / base else base
    }

    private fun loadedRangeEndSeconds(item: AVPlayerItem): Double? {
        var best: Double? = null
        for (entry in item.loadedTimeRanges) {
            val range = (entry as? NSValue)?.CMTimeRangeValue ?: continue
            val endSec = CMTimeGetSeconds(CMTimeRangeGetEnd(range))
            if (endSec.isFinite() && endSec >= 0.0 && (best == null || endSec > best)) {
                best = endSec
            }
        }
        return best
    }

    private fun applyPlaybackRate() {
        lastPlayCommandMark = TimeSource.Monotonic.markNow()
        if (currentSpeed == 1f) {
            player.play()
        } else {
            player.rate = currentSpeed
        }
    }

    override fun play() {
        onMain {
            if (released) return@onMain
            desiredPlaying = true
            isPlaying = true
            IosHostBridge.configurePlaybackAudioSession(TAG)
            if (readyEmitted && !isSeeking) {
                applyPlaybackRate()
            }
            updateBufferingState()
        }
    }

    override fun pause() {
        onMain {
            if (released) return@onMain
            desiredPlaying = false
            isPlaying = false
            lastPlayCommandMark = null
            player.pause()
            updateBufferingState()
        }
    }

    override fun seekTo(positionMillis: Long) {
        val clamped = positionMillis.coerceAtLeast(0L)
        onMain {
            if (released) return@onMain
            this.positionMillis = clamped
            val item = currentItem ?: return@onMain
            if (!readyEmitted) {
                pendingStartMillis = clamped
                return@onMain
            }
            val ticket = openTicket
            val gen = ++playbackGeneration
            isSeeking = true
            isBuffering = true
            val cmTarget = CMTimeMake(value = clamped, timescale = 1_000)
            player.seekToTime(
                cmTarget,
                toleranceBefore = kCMTimeZero.readValue(),
                toleranceAfter = kCMTimeZero.readValue(),
            ) { _ ->
                onMain {
                    if (released || ticket != openTicket || currentItem !== item) return@onMain
                    isSeeking = false
                    feedback.restarted(gen)
                    syncItemMetrics()
                    if (desiredPlaying) applyPlaybackRate() else player.pause()
                    updateBufferingState()
                }
            }
        }
    }

    override fun setSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 4.0f)
        onMain {
            if (released) return@onMain
            currentSpeed = clamped
            if (desiredPlaying && readyEmitted && !isSeeking) {
                player.rate = clamped
            }
        }
    }

    override fun setAspectRatio(mode: PlayerAspectRatio) {
        onMain {
            if (released) return@onMain
            currentAspectRatio = mode
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            playerLayer.videoGravity = currentVideoGravity()
            CATransaction.commit()
        }
    }

    override fun setRotation(degrees: Int) {
        val normalized = ((degrees % 360) + 360) % 360
        val snapped = (normalized / 90) * 90
        onMain {
            if (released) return@onMain
            currentRotationDegrees = snapped
            updateEffectiveVideoAspect()
        }
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        onMain {
            if (released) return@onMain
            currentVolume = clamped
            player.volume = clamped
            player.muted = clamped <= 0f
        }
    }

    override fun stop() {
        onMain {
            openTicket++
            detachCurrentItemLocked()
        }
    }

    override suspend fun beginStop() {
        withContext(Dispatchers.Main.immediate) {
            openTicket++
            detachCurrentItemLocked()
        }
    }

    override suspend fun stopAndAwaitIdle() {
        beginStop()
    }

    fun release() {
        onMain {
            if (released) return@onMain
            released = true
            openTicket++
            detachCurrentItemLocked()
            timeObserver?.let { token ->
                player.removeTimeObserver(token)
                timeObserver = null
            }
            playerLayer.player = null
            playerLayer.removeFromSuperlayer()
            scope.cancel()
        }
    }

    private fun detachCurrentItemLocked() {
        pollJob?.cancel()
        pollJob = null
        val center = NSNotificationCenter.defaultCenter
        itemObservers.forEach { center.removeObserver(it) }
        itemObservers.clear()

        desiredPlaying = false
        isPlaying = false
        isLoadingItem = false
        isSeeking = false
        isBuffering = false
        readyEmitted = false
        failureEmitted = false
        tracksLoaded = false
        pendingStartMillis = 0L
        lastPlayCommandMark = null
        positionMillis = 0L
        durationMillis = 0L
        bufferedPositionMillis = 0L
        rawVideoAspect = null
        videoAspect = null
        audioTracks = emptyList()
        subtitleTracks = emptyList()
        selectedAudioTrackId = null
        selectedSubtitleTrackId = null
        audioSelectionGroup = null
        subtitleSelectionGroup = null
        audioOptionsById = emptyMap()
        subtitleOptionsById = emptyMap()
        currentItem = null
        feedback.invalidate()

        player.pause()
        // 先让 AVPlayer 解除对回环 URL 的持有，再注销桥接令牌，防止收到 404
        player.replaceCurrentItemWithPlayerItem(null)
        wrappedUrl?.close()
        wrappedUrl = null
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (NSThread.isMainThread) {
            block()
        } else {
            dispatch_async(dispatch_get_main_queue()) { block() }
        }
    }

    private companion object {
        const val TAG = "IosPlayer"
        const val DEFAULT_DURATION_SECONDS = 3600L
        const val FORWARD_BUFFER_SECONDS = 15.0
        const val PREFERRED_TIMESCALE = 600
        const val PERIODIC_INTERVAL_SECONDS = 0.2
        const val READY_POLL_MS = 50L
        const val STEADY_POLL_MS = 200L
        const val PLAY_COMMAND_GRACE_MS = 250L
    }
}

/**
 * 承载 [IosPlaybackBackend.playerLayer] 的视频画面表面。
 * 禁用触摸拦截以将全部点按/滑动手势交由上层 Compose `MobilePlayerControls` 处理，
 * 并在旋转 90/270 度时通过 `CGAffineTransform` 等比适配容器边界。
 */
@Composable
internal fun IosVideoSurface(
    backend: IosPlaybackBackend,
    modifier: Modifier = Modifier,
) {
    val gravity = backend.currentVideoGravity()
    val rotationDegrees = backend.rotationDegrees
    UIKitView(
        factory = { IosVideoSurfaceHostView(backend.playerLayer, gravity, rotationDegrees) },
        modifier = modifier,
        update = { view -> view.sync(backend.playerLayer, gravity, rotationDegrees) },
        onRelease = { view -> view.detachLayer(backend.playerLayer) },
        properties = UIKitInteropProperties(
            isInteractive = false,
            isNativeAccessibilityEnabled = false,
        ),
    )
}

private class IosVideoSurfaceHostView(
    private var hostedLayer: AVPlayerLayer,
    initialGravity: AVLayerVideoGravity,
    private var rotationDegrees: Int,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {

    init {
        backgroundColor = UIColor.blackColor
        opaque = true
        clipsToBounds = true
        userInteractionEnabled = false
        attachLayer(hostedLayer, initialGravity)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        applyLayerGeometry()
    }

    fun sync(layer: AVPlayerLayer, gravity: AVLayerVideoGravity, rotation: Int) {
        if (hostedLayer !== layer || layer.superlayer !== this.layer) {
            attachLayer(layer, gravity)
        } else if (hostedLayer.videoGravity != gravity) {
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            hostedLayer.videoGravity = gravity
            CATransaction.commit()
        }
        if (rotationDegrees != rotation) {
            rotationDegrees = rotation
            applyLayerGeometry()
        }
    }

    fun detachLayer(layer: AVPlayerLayer) {
        if (layer.superlayer === this.layer) {
            layer.removeFromSuperlayer()
        }
    }

    private fun attachLayer(layer: AVPlayerLayer, gravity: AVLayerVideoGravity) {
        hostedLayer = layer
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        layer.videoGravity = gravity
        if (layer.superlayer !== this.layer) {
            layer.removeFromSuperlayer()
            this.layer.addSublayer(layer)
        }
        CATransaction.commit()
        applyLayerGeometry()
    }

    private fun applyLayerGeometry() {
        val (w, h) = bounds.useContents { size.width to size.height }
        if (w <= 0.0 || h <= 0.0) return
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        if (rotationDegrees == 0) {
            hostedLayer.setAffineTransform(CGAffineTransformIdentity.readValue())
            hostedLayer.frame = bounds
        } else {
            // 90/270 度旋转时交换图层逻辑宽高，使 AVPlayerLayer 在目标朝向下正确执行 Fit/Crop/Stretch
            val swapped = rotationDegrees % 180 != 0
            val layerW = if (swapped) h else w
            val layerH = if (swapped) w else h
            val radians = rotationDegrees.toDouble() * PI / 180.0
            hostedLayer.setAffineTransform(CGAffineTransformIdentity.readValue())
            hostedLayer.bounds = CGRectMake(0.0, 0.0, layerW, layerH)
            hostedLayer.position = platform.CoreGraphics.CGPointMake(w * 0.5, h * 0.5)
            hostedLayer.setAffineTransform(
                CGAffineTransformConcat(
                    CGAffineTransformMakeRotation(radians),
                    CGAffineTransformMakeScale(1.0, 1.0),
                ),
            )
        }
        CATransaction.commit()
    }
}
