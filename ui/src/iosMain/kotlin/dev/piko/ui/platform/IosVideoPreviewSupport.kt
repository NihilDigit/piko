@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.piko.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import dev.piko.shared.log.PikoLog
import dev.piko.shared.media.IosAvPlayerMediaBridge
import dev.piko.shared.media.WrappedAvPlayerUrl
import dev.piko.shared.media.player.ExternalSubtitle
import dev.piko.shared.media.player.PlaybackBackendEvent
import dev.piko.shared.media.player.PlaybackFeedbackTracker
import dev.piko.shared.media.player.PlaybackTarget
import dev.piko.shared.media.player.PlayerAspectRatio
import dev.piko.ui.ios.IosHostBridge
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
import platform.AVFoundation.*
import platform.CoreGraphics.CGRectMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreMedia.CMTimeRangeGetEnd
import platform.CoreMedia.kCMTimePositiveInfinity
import platform.CoreMedia.kCMTimeZero
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSValue
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.TimeSource

/**
 * iOS 片段预览与信息流播放支持：基于原生 `AVPlayer` + `AVPlayerLayer` + `UIKitView`，
 * 配合 [IosAvPlayerMediaBridge] 将回环代理的 MPEG-TS 切片包装为内存 HLS VOD 播放列表，
 * 使信息流与片段截取预览在 iOS 15.0 至 iOS 27+ 全版本稳定起播、定位与三槽位循环复用。
 */
internal class IosVideoPreviewSupport : VideoPreviewSupport {
    @Composable
    override fun rememberPreviewBackend(keyframeStart: Boolean): PreviewBackend =
        remember(keyframeStart) { IosPreviewBackend(keyframeStart = keyframeStart) }

    @Composable
    override fun Surface(backend: PreviewBackend, modifier: Modifier) {
        val iosBackend = backend as IosPreviewBackend
        val gravity = iosBackend.currentVideoGravity()
        UIKitView(
            factory = { IosPlayerLayerView(iosBackend.player, gravity) },
            modifier = modifier,
            update = { view -> view.attach(iosBackend.player, gravity) },
            onRelease = { view -> view.detach() },
            properties = UIKitInteropProperties(
                isInteractive = false,
                isNativeAccessibilityEnabled = false,
            ),
        )
    }
}

/**
 * 托管 [AVPlayerLayer] 的原生视图：透明底色以便 `Fit` 留边时露出底层 `ClipBackdrop`，
 * 禁用触摸拦截以免抢走 `VerticalPager` 滑动/点按手势，并在 `layoutSubviews` 内关闭隐式动画同步尺寸。
 */
private class IosPlayerLayerView(
    initialPlayer: AVPlayer,
    initialGravity: AVLayerVideoGravity,
) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val playerLayer: AVPlayerLayer = AVPlayerLayer.playerLayerWithPlayer(initialPlayer)

    init {
        backgroundColor = UIColor.clearColor
        opaque = false
        clipsToBounds = true
        userInteractionEnabled = false
        playerLayer.videoGravity = initialGravity
        playerLayer.backgroundColor = UIColor.clearColor.CGColor
        layer.addSublayer(playerLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.frame = bounds
        CATransaction.commit()
    }

    fun attach(player: AVPlayer, gravity: AVLayerVideoGravity) {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        if (playerLayer.player !== player) {
            playerLayer.player = player
        }
        if (playerLayer.videoGravity != gravity) {
            playerLayer.videoGravity = gravity
        }
        playerLayer.frame = bounds
        CATransaction.commit()
    }

    fun detach() {
        playerLayer.player = null
    }
}

internal class IosPreviewBackend(
    private val keyframeStart: Boolean,
) : PreviewBackend {
    val player: AVPlayer = AVPlayer().apply {
        actionAtItemEnd = AVPlayerActionAtItemEndPause
        automaticallyWaitsToMinimizeStalling = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val feedback = PlaybackFeedbackTracker()

    override val playbackGeneration: Long get() = feedback.generation
    override val bufferSnapshot: PlaybackBufferSnapshot? get() = feedback.snapshot

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
    override var videoAspect by mutableStateOf<Float?>(null)
        private set

    // 避免私有 setter 与 PlaybackBackend.setSpeed / setAspectRatio / setVolume 命名冲突
    private var currentSpeed by mutableFloatStateOf(1f)
    override val speed: Float get() = currentSpeed
    override val supportsSpeed: Boolean = true

    private var currentAspectRatio by mutableStateOf(PlayerAspectRatio.Fit)
    override val aspectRatio: PlayerAspectRatio get() = currentAspectRatio

    private var currentVolume by mutableFloatStateOf(1f)
    override val volume: Float get() = currentVolume

    private val _events = MutableSharedFlow<PlaybackBackendEvent>(extraBufferCapacity = 16)
    override val events: Flow<PlaybackBackendEvent> = _events.asSharedFlow()

    private var openTicket = 0L
    private var bufferAheadSeconds = DEFAULT_BUFFER_AHEAD_SECONDS
    private var desiredPlaying = false
    private var isLoadingItem = false
    private var isSeeking = false
    private var readyEmitted = false
    private var failureEmitted = false
    private var released = false
    private var audioSessionConfigured = false
    private var lastPlayCommandMark: TimeSource.Monotonic.ValueTimeMark? = null

    private var currentItem: AVPlayerItem? = null
    private var wrappedUrl: WrappedAvPlayerUrl? = null
    private var pollJob: Job? = null
    private val itemObservers = mutableListOf<NSObjectProtocol>()
    private var timeObserver: Any? = null

    init {
        timeObserver = player.addPeriodicTimeObserverForInterval(
            interval = CMTimeMakeWithSeconds(PERIODIC_INTERVAL_SECONDS, PREFERRED_TIMESCALE),
            queue = dispatch_get_main_queue(),
        ) { time ->
            if (released || currentItem == null) return@addPeriodicTimeObserverForInterval
            val sec = CMTimeGetSeconds(time)
            if (sec.isFinite() && sec >= 0.0 && !isSeeking) {
                positionMillis = (sec * 1000.0).roundToLong().coerceAtLeast(0L)
            }
            syncItemMetrics()
        }
    }

    fun currentVideoGravity(): AVLayerVideoGravity = when (currentAspectRatio) {
        PlayerAspectRatio.Crop, PlayerAspectRatio.RegionCrop -> AVLayerVideoGravityResizeAspectFill
        PlayerAspectRatio.Stretch -> AVLayerVideoGravityResize
        PlayerAspectRatio.Fit -> AVLayerVideoGravityResizeAspect
    }

    override suspend fun open(
        target: PlaybackTarget,
        startMillis: Long,
        playWhenReady: Boolean,
        subtitles: List<ExternalSubtitle>,
    ) {
        val preparedBridge: WrappedAvPlayerUrl?
        val nsUrl: NSURL? = when (target) {
            is PlaybackTarget.LocalFile -> {
                preparedBridge = IosAvPlayerMediaBridge.Shared.wrapLocalTsFile(target.path)
                if (preparedBridge != null) {
                    NSURL.URLWithString(preparedBridge.playbackUrl)
                } else {
                    NSURL.fileURLWithPath(target.path)
                }
            }

            is PlaybackTarget.Url -> {
                preparedBridge = IosAvPlayerMediaBridge.Shared.wrap(
                    url = target.url,
                    durationSeconds = DEFAULT_CLIP_DURATION_SECONDS,
                    forceHlsTs = target.isMpegTsTranscode,
                )
                NSURL.URLWithString(preparedBridge.playbackUrl)
            }
        }

        try {
            withContext(Dispatchers.Main.immediate) {
                if (released) {
                    preparedBridge?.close()
                    return@withContext
                }
                val ticket = ++openTicket
                detachCurrentItemLocked()

                if (nsUrl == null) {
                    preparedBridge?.close()
                    isLoadingItem = false
                    isBuffering = false
                    _events.tryEmit(PlaybackBackendEvent.Error("Invalid media URL"))
                    return@withContext
                }

                if (playWhenReady && currentVolume > 0f) {
                    ensureAudioSession()
                }

                val gen = feedback.invalidate()
                wrappedUrl = preparedBridge
                desiredPlaying = playWhenReady
                isPlaying = playWhenReady
                isLoadingItem = true
                isSeeking = false
                isBuffering = true
                readyEmitted = false
                failureEmitted = false
                positionMillis = startMillis.coerceAtLeast(0L)
                durationMillis = 0L
                bufferedPositionMillis = 0L
                videoAspect = null

                val asset = AVURLAsset.URLAssetWithURL(nsUrl, options = null)
                val item = AVPlayerItem.playerItemWithAsset(asset)
                item.preferredForwardBufferDuration = bufferAheadSeconds.toDouble()
                currentItem = item

                player.volume = currentVolume
                player.muted = currentVolume <= 0f
                player.replaceCurrentItemWithPlayerItem(item)
                attachItemNotifications(item, ticket)

                pollJob = scope.launch {
                    pollItemState(item, ticket, gen, startMillis.coerceAtLeast(0L))
                }
            }
        } catch (e: CancellationException) {
            preparedBridge?.close()
            throw e
        } catch (e: Throwable) {
            preparedBridge?.close()
            throw e
        }
    }

    private suspend fun pollItemState(
        item: AVPlayerItem,
        ticket: Long,
        gen: Long,
        initialStartMillis: Long,
    ) {
        while (scope.isActive && !released && ticket == openTicket && currentItem === item) {
            when (item.status) {
                AVPlayerItemStatusReadyToPlay -> {
                    syncItemMetrics()
                    if (!readyEmitted) {
                        readyEmitted = true
                        if (initialStartMillis > 0L) {
                            isSeeking = true
                            val targetTime = CMTimeMake(initialStartMillis, 1000)
                            val before = if (keyframeStart) kCMTimePositiveInfinity.readValue() else kCMTimeZero.readValue()
                            val after = kCMTimeZero.readValue()
                            player.seekToTime(
                                targetTime,
                                toleranceBefore = before,
                                toleranceAfter = after,
                            ) { _ ->
                                onMain {
                                    if (released || ticket != openTicket || currentItem !== item) return@onMain
                                    isSeeking = false
                                    isLoadingItem = false
                                    feedback.restarted(gen)
                                    syncItemMetrics()
                                    if (desiredPlaying) applyPlaybackRate() else player.pause()
                                    updateBufferingState()
                                    _events.tryEmit(PlaybackBackendEvent.Ready)
                                }
                            }
                        } else {
                            isLoadingItem = false
                            feedback.restarted(gen)
                            if (desiredPlaying) applyPlaybackRate() else player.pause()
                            updateBufferingState()
                            _events.tryEmit(PlaybackBackendEvent.Ready)
                        }
                    } else {
                        updateBufferingState()
                    }
                }

                AVPlayerItemStatusFailed -> {
                    emitFailureOnce(item.error)
                    return
                }

                else -> {
                    updateBufferingState()
                }
            }
            delay(if (readyEmitted) STEADY_POLL_MS else READY_POLL_MS)
        }
    }

    private fun attachItemNotifications(item: AVPlayerItem, ticket: Long) {
        val center = NSNotificationCenter.defaultCenter
        itemObservers += center.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            if (released || ticket != openTicket || currentItem !== item) return@addObserverForName
            desiredPlaying = false
            isPlaying = false
            isBuffering = false
            if (durationMillis > 0L) {
                positionMillis = durationMillis
            }
            _events.tryEmit(PlaybackBackendEvent.Ended)
        }
        itemObservers += center.addObserverForName(
            name = AVPlayerItemFailedToPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { notification ->
            if (released || ticket != openTicket || currentItem !== item) return@addObserverForName
            val nsError = notification?.userInfo?.get(AVPlayerItemFailedToPlayToEndTimeErrorKey) as? NSError
                ?: item.error
            emitFailureOnce(nsError)
        }
        itemObservers += center.addObserverForName(
            name = AVPlayerItemPlaybackStalledNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            if (released || ticket != openTicket || currentItem !== item) return@addObserverForName
            if (desiredPlaying) {
                isBuffering = true
            }
        }
    }

    private fun emitFailureOnce(error: NSError?) {
        if (failureEmitted) return
        failureEmitted = true
        isLoadingItem = false
        isSeeking = false
        isBuffering = false
        val detail = error?.let { "${it.domain} (${it.code}): ${it.localizedDescription}" } ?: "AVPlayerItem failed"
        PikoLog.w(TAG, "iOS preview playback error: $detail")
        _events.tryEmit(PlaybackBackendEvent.Error(detail))
    }

    private fun syncItemMetrics() {
        val item = currentItem ?: return
        val durSec = CMTimeGetSeconds(item.duration)
        if (durSec.isFinite() && durSec > 0.0) {
            val durMs = (durSec * 1000.0).roundToLong()
            if (durMs > 0L) durationMillis = durMs
        }
        if (!isSeeking) {
            val posSec = CMTimeGetSeconds(player.currentTime())
            if (posSec.isFinite() && posSec >= 0.0) {
                positionMillis = (posSec * 1000.0).roundToLong().coerceAtLeast(0L)
            }
        }
        if (videoAspect == null) {
            resolveVideoAspect(item)?.let { videoAspect = it }
        }
        val bufferedEndSec = loadedRangeEndSeconds(item)
        if (bufferedEndSec != null) {
            bufferedPositionMillis = (bufferedEndSec * 1000.0).roundToLong().coerceAtLeast(0L)
            val aheadSec = (bufferedEndSec - positionMillis / 1000.0).coerceAtLeast(0.0)
            feedback.sample(feedback.generation, aheadSec, currentSpeed)
        }
        if (readyEmitted && !isLoadingItem && !isSeeking) {
            when (player.timeControlStatus) {
                AVPlayerTimeControlStatusPlaying -> {
                    desiredPlaying = true
                    isPlaying = true
                }
                AVPlayerTimeControlStatusPaused -> {
                    val recentlyRequestedPlay = lastPlayCommandMark?.let {
                        it.elapsedNow().inWholeMilliseconds < PLAY_COMMAND_GRACE_MS
                    } == true
                    if (desiredPlaying && !recentlyRequestedPlay && player.rate == 0f) {
                        desiredPlaying = false
                        isPlaying = false
                    }
                }
            }
        }
        updateBufferingState()
    }

    private fun updateBufferingState() {
        val item = currentItem
        if (item == null || released) {
            isBuffering = false
            return
        }
        val waiting = desiredPlaying &&
            player.timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
        isBuffering = isLoadingItem || isSeeking || waiting
    }

    private fun resolveVideoAspect(item: AVPlayerItem): Float? {
        val (pw, ph) = item.presentationSize.useContents { width to height }
        if (pw > 0.0 && ph > 0.0) {
            return (pw / ph).toFloat()
        }
        val track = item.asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
            ?: return null
        val (nw, nh) = track.naturalSize.useContents { width to height }
        if (nw <= 0.0 || nh <= 0.0) return null
        val rotated = track.preferredTransform.useContents { abs(b) > abs(a) }
        val w = if (rotated) nh else nw
        val h = if (rotated) nw else nh
        return if (w > 0.0 && h > 0.0) (w / h).toFloat() else null
    }

    private fun loadedRangeEndSeconds(item: AVPlayerItem): Double? {
        var maxEnd = -1.0
        for (entry in item.loadedTimeRanges) {
            val value = entry as? NSValue ?: continue
            val range = value.CMTimeRangeValue
            val end = CMTimeGetSeconds(CMTimeRangeGetEnd(range))
            if (end.isFinite() && end > maxEnd) {
                maxEnd = end
            }
        }
        return maxEnd.takeIf { it >= 0.0 }
    }

    private fun applyPlaybackRate() {
        if (released || currentItem == null) return
        if (currentVolume > 0f) {
            ensureAudioSession()
        }
        lastPlayCommandMark = TimeSource.Monotonic.markNow()
        player.play()
        if (abs(currentSpeed - 1f) > 0.01f) {
            player.rate = currentSpeed
        }
    }

    private fun ensureAudioSession() {
        if (audioSessionConfigured) return
        audioSessionConfigured = true
        IosHostBridge.configurePlaybackAudioSession(TAG)
    }

    override fun play() {
        onMain {
            if (released) return@onMain
            desiredPlaying = true
            isPlaying = true
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
            player.pause()
            updateBufferingState()
        }
    }

    override fun seekTo(positionMillis: Long) {
        val targetMs = positionMillis.coerceAtLeast(0L)
        onMain {
            if (released) return@onMain
            val item = currentItem ?: return@onMain
            val ticket = openTicket
            val gen = feedback.invalidate()
            isSeeking = true
            isBuffering = true
            this.positionMillis = targetMs
            val targetTime = CMTimeMake(targetMs, 1000)
            val before = if (keyframeStart) kCMTimePositiveInfinity.readValue() else kCMTimeZero.readValue()
            val after = kCMTimeZero.readValue()
            player.seekToTime(
                targetTime,
                toleranceBefore = before,
                toleranceAfter = after,
            ) { _ ->
                onMain {
                    if (released || ticket != openTicket || currentItem !== item) return@onMain
                    isSeeking = false
                    feedback.restarted(gen)
                    syncItemMetrics()
                    if (desiredPlaying) applyPlaybackRate()
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
            currentAspectRatio = mode
        }
    }

    override fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        onMain {
            if (released) return@onMain
            currentVolume = clamped
            player.volume = clamped
            player.muted = clamped <= 0f
            if (clamped > 0f && desiredPlaying) {
                ensureAudioSession()
            }
        }
    }

    override fun setBufferAhead(seconds: Int) {
        val clamped = seconds.coerceAtLeast(1)
        onMain {
            bufferAheadSeconds = clamped
            currentItem?.preferredForwardBufferDuration = clamped.toDouble()
        }
    }

    override fun bufferReport(): String {
        val aheadSec = ((bufferedPositionMillis - positionMillis).coerceAtLeast(0L)) / 1000L
        return "forward=${bufferAheadSeconds}s, buffered=${aheadSec}s"
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

    override fun release() {
        onMain {
            if (released) return@onMain
            released = true
            openTicket++
            detachCurrentItemLocked()
            timeObserver?.let { token ->
                player.removeTimeObserver(token)
                timeObserver = null
            }
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
        positionMillis = 0L
        durationMillis = 0L
        bufferedPositionMillis = 0L
        videoAspect = null
        currentItem = null
        feedback.invalidate()

        player.pause()
        // 先让 AVPlayer 解除对回环 URL 的持有，再注销桥接令牌
        player.replaceCurrentItemWithPlayerItem(null)
        wrappedUrl?.close()
        wrappedUrl = null
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (platform.Foundation.NSThread.isMainThread) {
            block()
        } else {
            dispatch_async(dispatch_get_main_queue()) { block() }
        }
    }

    private companion object {
        const val TAG = "IosPreview"
        const val DEFAULT_BUFFER_AHEAD_SECONDS = 2
        const val DEFAULT_CLIP_DURATION_SECONDS = 180L
        const val PREFERRED_TIMESCALE = 600
        const val PERIODIC_INTERVAL_SECONDS = 0.1
        const val READY_POLL_MS = 50L
        const val STEADY_POLL_MS = 180L
        const val PLAY_COMMAND_GRACE_MS = 150L
    }
}
