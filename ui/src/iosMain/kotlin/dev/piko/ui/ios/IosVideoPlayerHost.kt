@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.piko.ui.ios

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.piko.data.auth.AppLanguage
import dev.piko.shared.i18n.PikoLocale
import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import dev.piko.shared.media.IosAvPlayerMediaBridge
import dev.piko.shared.media.PreparedPlayback
import dev.piko.shared.media.WrappedAvPlayerUrl
import dev.piko.shared.media.player.PlayHistoryReporter
import dev.piko.shared.notice.PikoErrorMapper
import dev.piko.shared.state.launchOnAccountLeave
import dev.piko.ui.ClipFeedLinks
import dev.piko.ui.LocalAppLanguage
import dev.piko.ui.LocalPikoServices
import dev.piko.ui.PikoServices
import dev.piko.ui.VideoPlayerHost
import dev.piko.ui.VideoPlayerRequest
import dev.piko.ui.components.LocalPointerSource
import dev.piko.ui.components.PointerSource
import dev.piko.ui.platform.LocalPikoPlatform
import dev.piko.ui.platform.PikoPlatform
import dev.piko.ui.screens.clips.ClipFeedScreen
import dev.piko.ui.screens.player.LocalPlayerLayoutPrefs
import dev.piko.ui.theme.PikoTheme
import dev.piko.ui.theme.appearanceFlow
import io.github.nihildigit.pikpak.PikPakClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFoundation.*
import platform.AVKit.AVPlayerViewController
import platform.AVKit.AVPlayerViewControllerDelegateProtocol
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.*
import platform.darwin.NSObject
import platform.darwin.NSObjectProtocol
import kotlin.coroutines.resume
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** The process-owned session serializes UIKit state on the main dispatcher. */
fun iosVideoPlayerHost(
    services: PikoServices,
    scope: CoroutineScope,
    platform: PikoPlatform? = null,
): VideoPlayerHost.Detached {
    val session = PlayerSession(services, scope, platform)
    scope.launchOnAccountLeave(services.clientManager) { session.close() }
    return VideoPlayerHost.Detached(
        open = session::open,
        openClipFeed = session::openClipFeed,
        isClipFeedOpen = session::isClipFeedOpen,
        closeClipFeed = session::closeClipFeed,
        hasActivePlayer = session::isActive,
    )
}

private class PlayerSession(
    private val services: PikoServices,
    private val scope: CoroutineScope,
    private val platform: PikoPlatform?,
) {
    private var generation = 0L
    private var preparing: Job? = null
    private var monitorJob: Job? = null
    private var playback: PreparedPlayback? = null
    private var wrappedUrl: WrappedAvPlayerUrl? = null
    private var controller: AVPlayerViewController? = null
    private var dismissalObserver: PlayerDismissalObserver? = null
    private var pipDelegate: PlayerPiPDelegate? = null
    private var pipActive: Boolean = false
    private var pipRestoringUi: Boolean = false
    private var modalDismissedIntoPip: Boolean = false
    private val activeObservers = mutableListOf<NSObjectProtocol>()

    // 当前活跃播放上下文：用于暂停、退后台、自然播完与关闭时的续播进度保存与云端上报
    private var activeSessionClient: PikPakClient? = null
    private var activeFileId: String = ""
    private var activePositionKey: String = ""
    private var activeTitle: String = ""
    private var activeDurationMs: Long = 0L
    private var pendingInitialSeekMs: Long = 0L
    private var lastReportedCloudSec: Long = -1L
    private var wasPlaying: Boolean = false
    private var subtitlesAutoSelected: Boolean = false

    // 独立弹出信息流状态（iPad/宽屏从信息流顶栏点击「在独立窗口播放」时以全屏模态页呈现）
    private var clipFeedOpenState by mutableStateOf(false)
    private var clipFeedController: UIViewController? = null
    private var clipFeedDismissalObserver: PlayerDismissalObserver? = null
    private var clipFeedDismissing: CompletableDeferred<Unit>? = null

    fun isActive(): Boolean = controller != null || preparing?.isActive == true

    fun isClipFeedOpen(): Boolean = clipFeedOpenState

    fun openClipFeed(links: ClipFeedLinks) {
        val currentPlatform = platform ?: run {
            PikoLog.d(TAG, "Detached clip feed host skipped: platform not bound")
            return
        }
        val host = IosHostBridge.topViewController() ?: return
        if (clipFeedController != null) return
        val modal = ComposeUIViewController {
            val initialAppearance = remember {
                val snap = services.preferences.currentSnapshot
                val legacyEink = snap.themeSeed.equals(dev.piko.ui.theme.SeedTheme.EINK.name, ignoreCase = true)
                dev.piko.ui.theme.Appearance(
                    mode = dev.piko.ui.theme.ThemeMode.entries.find { m -> m.name.equals(snap.themeMode, ignoreCase = true) }
                        ?: dev.piko.ui.theme.ThemeMode.SYSTEM,
                    seed = dev.piko.ui.theme.SeedTheme.colorEntries.find { s -> s.name == snap.themeSeed },
                    dynamicColorAvailable = false,
                    pureBlack = snap.pureBlack,
                    einkMode = snap.einkMode || legacyEink,
                    motionPreference = snap.motionPreference,
                )
            }
            val appearance by services.preferences.appearanceFlow(dynamicColorAvailable = false)
                .collectAsStateWithLifecycle(initialValue = initialAppearance)
            val effectiveLanguage by PikoLocale.effectiveLanguageState
            val layoutPrefs by services.preferences.playerLayoutPrefsFlow.collectAsStateWithLifecycle(
                initialValue = services.preferences.currentSnapshot.playerLayout,
            )
            val pointerSource = remember { PointerSource(initialTouchLike = true) }
            CompositionLocalProvider(
                LocalAppLanguage provides effectiveLanguage,
                LocalPikoServices provides services,
                LocalPikoPlatform provides currentPlatform,
                LocalPointerSource provides pointerSource,
                LocalPlayerLayoutPrefs provides layoutPrefs,
            ) {
                PikoTheme(appearance = appearance) {
                    ClipFeedScreen(
                        onBackClick = {
                            closeClipFeed()
                            links.close()
                        },
                        onPlayFull = { file, startMs ->
                            closeClipFeed()
                            links.playFull(file, startMs)
                        },
                        onLocate = { file ->
                            closeClipFeed()
                            links.locate(file)
                        },
                        compact = false,
                        onDock = {
                            closeClipFeed()
                            links.dock()
                        },
                    )
                }
            }
        }
        modal.modalPresentationStyle = UIModalPresentationFullScreen
        val observer = PlayerDismissalObserver {
            if (clipFeedController === modal) {
                clipFeedController = null
                clipFeedDismissalObserver?.onClosed = null
                clipFeedDismissalObserver = null
                clipFeedOpenState = false
            }
        }
        modal.transitioningDelegate = observer
        clipFeedController = modal
        clipFeedDismissalObserver = observer
        clipFeedOpenState = true
        host.presentViewController(modal, animated = true, completion = null)
    }

    fun closeClipFeed() {
        val current = clipFeedController
        clipFeedController = null
        clipFeedDismissalObserver?.onClosed = null
        clipFeedDismissalObserver = null
        clipFeedOpenState = false
        current?.transitioningDelegate = null
        if (current != null && (current.presentingViewController != null || !current.beingDismissed)) {
            val done = CompletableDeferred<Unit>()
            clipFeedDismissing = done
            current.dismissViewControllerAnimated(true) {
                done.complete(Unit)
                if (clipFeedDismissing === done) {
                    clipFeedDismissing = null
                }
            }
        }
    }

    fun close() {
        generation++
        preparing?.cancel()
        preparing = null
        closeClipFeed()
        val previous = releasePlayer()
        if (previous != null && (previous.presentingViewController != null || previous.presentedViewController != null)) {
            previous.dismissViewControllerAnimated(false, completion = null)
        }
    }

    /** Detach the native input before closing the bridge and proxy it was reading. */
    private fun releasePlayer(flushProgress: Boolean = true): AVPlayerViewController? {
        if (flushProgress) {
            flushActiveProgress(urgent = true, triggerFlush = true)
        }
        monitorJob?.cancel()
        monitorJob = null
        val center = NSNotificationCenter.defaultCenter
        activeObservers.forEach { center.removeObserver(it) }
        activeObservers.clear()

        val previous = controller
        controller = null
        dismissalObserver?.onClosed = null
        dismissalObserver = null
        pipDelegate?.clearCallbacks()
        pipDelegate = null
        pipActive = false
        pipRestoringUi = false
        modalDismissedIntoPip = false
        previous?.delegate = null
        previous?.transitioningDelegate = null
        previous?.player?.pause()
        previous?.player?.replaceCurrentItemWithPlayerItem(null)
        previous?.player = null
        wrappedUrl?.close()
        wrappedUrl = null
        playback?.close()
        playback = null

        activeSessionClient = null
        activeFileId = ""
        activePositionKey = ""
        activeTitle = ""
        activeDurationMs = 0L
        pendingInitialSeekMs = 0L
        lastReportedCloudSec = -1L
        wasPlaying = false
        subtitlesAutoSelected = false
        return previous
    }

    private suspend fun awaitPresentableHost(): UIViewController? {
        clipFeedDismissing?.let { pending ->
            withTimeoutOrNull(MODAL_TRANSITION_TIMEOUT_MS) { pending.await() }
        }
        val deadline = TimeSource.Monotonic.markNow() + MODAL_TRANSITION_TIMEOUT_MS.milliseconds
        while (TimeSource.Monotonic.markNow() < deadline) {
            var host = IosHostBridge.topViewController() ?: return null
            while (host.beingDismissed) {
                host = host.presentingViewController ?: break
            }
            val dismissing = when {
                host.beingDismissed -> host
                host.presentedViewController?.beingDismissed == true -> host.presentedViewController
                else -> null
            }
            if (dismissing == null) {
                return host
            }
            val coordinator = dismissing.transitionCoordinator
            if (coordinator != null) {
                withTimeoutOrNull(400L) {
                    suspendCancellableCoroutine<Unit> { cont ->
                        val scheduled = coordinator.animateAlongsideTransition(animation = null) { _ ->
                            if (cont.isActive) cont.resume(Unit)
                        }
                        if (!scheduled && cont.isActive) cont.resume(Unit)
                    }
                }
            } else {
                delay(32L)
            }
        }
        var fallback = IosHostBridge.topViewController() ?: return null
        while (fallback.beingDismissed) {
            fallback = fallback.presentingViewController ?: break
        }
        return fallback
    }

    fun open(request: VideoPlayerRequest) {
        val ticket = ++generation
        val sessionClient = services.clientManager.currentClient.value
        val account = sessionClient?.account
        preparing?.cancel()
        preparing = scope.launch(Dispatchers.Main.immediate) {
            var prepared: PreparedPlayback? = null
            var bridgeHandle: WrappedAvPlayerUrl? = null
            try {
                releasePlayer(flushProgress = true)?.let { previous ->
                    if (previous.presentingViewController != null || previous.presentedViewController != null) {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            previous.dismissViewControllerAnimated(false) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                        }
                    }
                }
                val targetLog = logFile(request.fileId, request.fileName)
                val positionKey = request.fileId.ifBlank { request.localPath.orEmpty() }
                val explicitStart = request.startMillis?.coerceAtLeast(0L)
                val prepareStarted = TimeSource.Monotonic.markNow()
                val cloudPosJob = if (explicitStart == null && request.fileId.isNotBlank()) {
                    scope.async { services.mediaRepository.cloudPlaybackPosition(request.fileId) }
                } else {
                    null
                }
                val localSaved = if (explicitStart == null && positionKey.isNotBlank()) {
                    services.mediaRepository.getPlaybackPosition(positionKey).takeIf { it > RESUME_THRESHOLD_MILLIS }
                } else {
                    null
                }

                val url: NSURL? = if (!request.localPath.isNullOrBlank()) {
                    bridgeHandle = IosAvPlayerMediaBridge.Shared.wrapLocalTsFile(request.localPath)
                    if (bridgeHandle != null) {
                        NSURL.URLWithString(bridgeHandle.playbackUrl)
                    } else {
                        NSURL.fileURLWithPath(request.localPath)
                    }
                } else {
                    val result = services.mediaRepository.preparePlayback(request.fileId)
                        .logFailure(TAG, "Failed to prepare playback for $targetLog")
                    prepared = result.getOrNull()
                    if (ticket != generation || account != services.clientManager.currentClient.value?.account) {
                        cloudPosJob?.cancel()
                        return@launch
                    }
                    // Audio files and unindexed videos without a GCID bypass the loopback proxy and expose a direct CDN URL.
                    val rawUrl = (prepared?.proxyUrl ?: prepared?.info?.currentUrl)?.takeIf { it.isNotBlank() }
                    if (rawUrl == null) {
                        cloudPosJob?.cancel()
                        if (result.isSuccess) {
                            PikoLog.w(TAG, "No playable URL available for $targetLog")
                        }
                        val message = result.exceptionOrNull()?.let {
                            PikoErrorMapper.failure(tr("播放", "Play", "再生", "재생", "播放"), it)
                        } ?: tr("无法播放此文件", "Unable to play this file", "このファイルを再生できません", "이 파일을 재생할 수 없습니다", "無法播放此檔案")
                        presentPlaybackError(message)
                        return@launch
                    }
                    val durationSec = prepared?.info?.durationSeconds?.takeIf { it > 0L } ?: DEFAULT_HLS_DURATION_SECONDS
                    val isTranscodeTs = prepared?.info?.isOrigin == false
                    bridgeHandle = IosAvPlayerMediaBridge.Shared.wrap(
                        url = rawUrl,
                        durationSeconds = durationSec,
                        forceHlsTs = isTranscodeTs,
                    )
                    NSURL.URLWithString(bridgeHandle.playbackUrl)
                }

                // A stale preparation never acquires ownership of a newly opened player.
                if (ticket != generation || account != services.clientManager.currentClient.value?.account) {
                    cloudPosJob?.cancel()
                    return@launch
                }
                if (url == null) {
                    cloudPosJob?.cancel()
                    PikoLog.w(TAG, "Invalid playback URL for $targetLog")
                    presentPlaybackError(tr("无法播放此文件", "Unable to play this file", "このファイルを再生できません", "이 파일을 재생할 수 없습니다", "無法播放此檔案"))
                    return@launch
                }

                val resumeStartMs = if (explicitStart != null) {
                    explicitStart
                } else {
                    val waitBudget = if (localSaved != null) LOCAL_RESUME_WAIT_MILLIS else CLOUD_RESUME_WAIT_MILLIS
                    val remaining = (waitBudget - prepareStarted.elapsedNow().inWholeMilliseconds).coerceAtLeast(0L)
                    val cloud = if (cloudPosJob?.isCompleted == true) {
                        runCatching { cloudPosJob.await() }.getOrNull()
                    } else {
                        withTimeoutOrNull(remaining) { runCatching { cloudPosJob?.await() }.getOrNull() }
                    }
                    cloudPosJob?.cancel()
                    val candidate = (cloud ?: localSaved)?.takeIf { it > RESUME_THRESHOLD_MILLIS } ?: 0L
                    val knownDurationMs = (prepared?.info?.durationSeconds ?: 0L) * 1_000L
                    if (knownDurationMs > 0L && candidate >= knownDurationMs - NEAR_END_MILLIS) 0L else candidate
                }

                val host = awaitPresentableHost()
                if (ticket != generation || account != services.clientManager.currentClient.value?.account) {
                    return@launch
                }
                if (host == null) {
                    PikoLog.w(TAG, "Cannot present player for $targetLog: no active UIViewController")
                    return@launch
                }

                IosHostBridge.configurePlaybackAudioSession(TAG)

                val isBridgedHls = bridgeHandle?.isHls == true
                val deferInitialSeek = resumeStartMs > 0L && isBridgedHls

                val asset = AVURLAsset.URLAssetWithURL(url, options = null)
                val item = AVPlayerItem.playerItemWithAsset(asset)
                val avPlayer = AVPlayer.playerWithPlayerItem(item)
                val next = AVPlayerViewController()
                next.player = avPlayer
                next.allowsPictureInPicturePlayback = true
                next.updatesNowPlayingInfoCenter = true
                next.modalPresentationStyle = UIModalPresentationFullScreen
                val observer = PlayerDismissalObserver {
                    if (controller === next) {
                        if (pipActive) {
                            modalDismissedIntoPip = true
                        } else {
                            generation++
                            releasePlayer(flushProgress = true)
                        }
                    }
                }
                val delegate = PlayerPiPDelegate(
                    onWillStart = {
                        if (controller === next) {
                            pipActive = true
                            pipRestoringUi = false
                        }
                    },
                    onFailed = {
                        if (controller === next) {
                            pipActive = false
                            pipRestoringUi = false
                            if (modalDismissedIntoPip) {
                                generation++
                                releasePlayer(flushProgress = true)
                            }
                        }
                    },
                    onDidStop = {
                        if (controller === next) {
                            pipActive = false
                            val wasRestoring = pipRestoringUi
                            pipRestoringUi = false
                            if (!wasRestoring && (modalDismissedIntoPip || next.presentingViewController == null)) {
                                generation++
                                releasePlayer(flushProgress = true)
                            }
                        }
                    },
                    onRestoreUi = restore@{ vc, complete ->
                        if (controller !== next || vc !== next) {
                            complete(false)
                            return@restore
                        }
                        pipRestoringUi = true
                        if (!modalDismissedIntoPip && next.presentingViewController != null) {
                            complete(true)
                            return@restore
                        }
                        scope.launch(Dispatchers.Main.immediate) {
                            val restoreHost = awaitPresentableHost()
                            if (controller !== next || restoreHost == null) {
                                pipRestoringUi = false
                                complete(false)
                                return@launch
                            }
                            modalDismissedIntoPip = false
                            next.transitioningDelegate = dismissalObserver
                            restoreHost.presentViewController(next, animated = true) {
                                complete(true)
                            }
                        }
                    },
                )
                next.transitioningDelegate = observer
                next.delegate = delegate
                controller = next
                dismissalObserver = observer
                pipDelegate = delegate
                pipActive = false
                pipRestoringUi = false
                modalDismissedIntoPip = false
                wrappedUrl = bridgeHandle
                bridgeHandle = null
                playback = prepared
                prepared = null

                activeSessionClient = sessionClient
                activeFileId = request.fileId
                activePositionKey = positionKey
                activeTitle = request.fileName
                activeDurationMs = (playback?.info?.durationSeconds ?: 0L) * 1_000L
                pendingInitialSeekMs = if (deferInitialSeek) resumeStartMs else 0L
                lastReportedCloudSec = -1L
                wasPlaying = false
                subtitlesAutoSelected = false

                attachSessionObservers(next, item, ticket)
                monitorJob = scope.launch {
                    monitorPlaybackLoop(next, item, ticket, targetLog)
                }

                host.presentViewController(next, animated = true, completion = null)
                if (!deferInitialSeek) {
                    if (resumeStartMs > 0L) {
                        PikoLog.d(TAG, "Resuming iOS AVPlayer at ${resumeStartMs}ms: $targetLog")
                        avPlayer.seekToTime(CMTimeMake(resumeStartMs, 1000))
                    }
                    avPlayer.play()
                }
            } finally {
                // Includes missing proxy URLs, cancelled preparations, and unavailable UIKit hosts.
                bridgeHandle?.close()
                prepared?.close()
            }
        }
    }

    private fun attachSessionObservers(
        playerController: AVPlayerViewController,
        item: AVPlayerItem,
        ticket: Long,
    ) {
        val center = NSNotificationCenter.defaultCenter
        activeObservers += center.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            if (controller !== playerController || ticket != generation) return@addObserverForName
            val durMs = resolveDurationMillis(item)
            if (activePositionKey.isNotBlank()) {
                services.mediaRepository.savePlaybackPositionAsync(activePositionKey, 0L)
            }
            if (activeFileId.isNotBlank() && durMs > 0L) {
                services.mediaRepository.enqueuePlayHistory(
                    fileId = activeFileId,
                    positionMillis = durMs,
                    durationMillis = durMs,
                    urgent = true,
                    title = activeTitle,
                    client = activeSessionClient,
                )
            }
        }
        activeObservers += center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ ->
            if (controller !== playerController || ticket != generation) return@addObserverForName
            flushActiveProgress(urgent = true, triggerFlush = true)
        }
    }

    private suspend fun monitorPlaybackLoop(
        playerController: AVPlayerViewController,
        item: AVPlayerItem,
        ticket: Long,
        targetLog: String,
    ) {
        var lastPeriodicPersistAt = TimeSource.Monotonic.markNow()
        while (scope.isActive && controller === playerController && ticket == generation) {
            val avPlayer = playerController.player ?: break
            when (item.status) {
                AVPlayerItemStatusReadyToPlay -> {
                    if (!subtitlesAutoSelected) {
                        subtitlesAutoSelected = true
                        selectPreferredEmbeddedSubtitle(item)
                    }
                    val deferredSeek = pendingInitialSeekMs
                    if (deferredSeek > 0L) {
                        pendingInitialSeekMs = 0L
                        PikoLog.d(TAG, "Resuming bridged iOS AVPlayer at ${deferredSeek}ms: $targetLog")
                        avPlayer.seekToTime(CMTimeMake(deferredSeek, 1000)) { _ ->
                            if (controller === playerController && ticket == generation) {
                                avPlayer.play()
                            }
                        }
                    }
                }
                AVPlayerItemStatusFailed -> {
                    pendingInitialSeekMs = 0L
                }
            }
            val durMs = resolveDurationMillis(item)
            if (durMs > 0L) activeDurationMs = durMs
            val playingNow = avPlayer.rate > 0f &&
                avPlayer.timeControlStatus != AVPlayerTimeControlStatusPaused
            if (playingNow) {
                wasPlaying = true
                if (lastPeriodicPersistAt.elapsedNow().inWholeMilliseconds >= PERIODIC_PERSIST_INTERVAL_MS) {
                    lastPeriodicPersistAt = TimeSource.Monotonic.markNow()
                    flushActiveProgress(urgent = false, triggerFlush = false)
                }
            } else if (wasPlaying) {
                // 从播放转为暂停时立即持久化本地进度并触发紧急云端上报
                wasPlaying = false
                flushActiveProgress(urgent = true, triggerFlush = false)
            }
            val pollInterval = if (pendingInitialSeekMs > 0L || !subtitlesAutoSelected) {
                READY_POLL_MS
            } else {
                MONITOR_POLL_MS
            }
            delay(pollInterval)
        }
    }

    private fun flushActiveProgress(urgent: Boolean, triggerFlush: Boolean) {
        if (pendingInitialSeekMs > 0L) return
        val avPlayer = controller?.player ?: return
        val item = avPlayer.currentItem
        val posSec = CMTimeGetSeconds(avPlayer.currentTime())
        if (!posSec.isFinite() || posSec < 0.0) return
        val posMs = (posSec * 1_000.0).roundToLong().coerceAtLeast(0L)
        val durMs = item?.let(::resolveDurationMillis)?.takeIf { it > 0L } ?: activeDurationMs

        if (activePositionKey.isNotBlank()) {
            val toSave = when {
                durMs > 0L && posMs >= durMs - NEAR_END_MILLIS -> 0L
                posMs > MIN_PERSIST_MILLIS -> posMs
                else -> null
            }
            if (toSave != null) {
                services.mediaRepository.savePlaybackPositionAsync(activePositionKey, toSave)
            }
        }
        if (activeFileId.isNotBlank() && posMs >= PlayHistoryReporter.MIN_REPORT_POSITION_MILLIS && durMs > 0L) {
            val posSecLong = posMs / 1_000L
            if (urgent || posSecLong != lastReportedCloudSec) {
                if (services.mediaRepository.enqueuePlayHistory(
                        fileId = activeFileId,
                        positionMillis = posMs,
                        durationMillis = durMs,
                        urgent = urgent,
                        title = activeTitle,
                        client = activeSessionClient,
                    )
                ) {
                    lastReportedCloudSec = posSecLong
                }
            }
        }
        if (triggerFlush) {
            scope.launch {
                services.mediaRepository.flushPlayHistory(BACKGROUND_FLUSH_TIMEOUT_MS)
            }
        }
    }

    private fun resolveDurationMillis(item: AVPlayerItem): Long {
        val sec = CMTimeGetSeconds(item.duration)
        if (sec.isFinite() && sec > 0.0) {
            return (sec * 1_000.0).roundToLong()
        }
        return activeDurationMs
    }

    /** 按应用当前有效语言自动优先选中匹配的内封字幕轨（仍可在系统播放器内自由切换）。 */
    private fun selectPreferredEmbeddedSubtitle(item: AVPlayerItem) {
        val group = item.asset.mediaSelectionGroupForMediaCharacteristic(AVMediaCharacteristicLegible) ?: return
        val options = group.options.filterIsInstance<AVMediaSelectionOption>()
        if (options.isEmpty()) return
        val preferredTags = when (PikoLocale.effectiveLanguage) {
            AppLanguage.ZH_HANT -> listOf("zh-hant", "zh-tw", "zh-hk", "cht", "zht", "zh")
            AppLanguage.JA -> listOf("ja", "jpn")
            AppLanguage.KO -> listOf("ko", "kor")
            AppLanguage.EN -> listOf("en", "eng")
            AppLanguage.ZH_CN, AppLanguage.SYSTEM -> listOf("zh-hans", "zh-cn", "chs", "zhs", "zh")
        }
        val matched = preferredTags.firstNotNullOfOrNull { tag ->
            options.firstOrNull { option ->
                val lang = option.extendedLanguageTag?.lowercase().orEmpty()
                val title = option.displayName.lowercase()
                lang == tag || lang.startsWith("$tag-") || title.contains(tag)
            }
        } ?: return
        item.selectMediaOption(matched, inMediaSelectionGroup = group)
    }

    private companion object {
        const val DEFAULT_HLS_DURATION_SECONDS = 180L
        const val RESUME_THRESHOLD_MILLIS = 3_000L
        const val MIN_PERSIST_MILLIS = 3_000L
        const val NEAR_END_MILLIS = 10_000L
        const val LOCAL_RESUME_WAIT_MILLIS = 350L
        const val CLOUD_RESUME_WAIT_MILLIS = 1_500L
        const val PERIODIC_PERSIST_INTERVAL_MS = 10_000L
        const val READY_POLL_MS = 50L
        const val MONITOR_POLL_MS = 1_000L
        const val BACKGROUND_FLUSH_TIMEOUT_MS = 2_000L
        const val MODAL_TRANSITION_TIMEOUT_MS = 1_500L
    }
}

private class PlayerPiPDelegate(
    var onWillStart: (() -> Unit)?,
    var onFailed: (() -> Unit)?,
    var onDidStop: (() -> Unit)?,
    var onRestoreUi: ((AVPlayerViewController, (Boolean) -> Unit) -> Unit)?,
) : NSObject(), AVPlayerViewControllerDelegateProtocol {
    fun clearCallbacks() {
        onWillStart = null
        onFailed = null
        onDidStop = null
        onRestoreUi = null
    }

    override fun playerViewControllerWillStartPictureInPicture(
        playerViewController: AVPlayerViewController,
    ) {
        onWillStart?.invoke()
    }

    override fun playerViewController(
        playerViewController: AVPlayerViewController,
        failedToStartPictureInPictureWithError: NSError,
    ) {
        onFailed?.invoke()
    }

    override fun playerViewControllerDidStopPictureInPicture(
        playerViewController: AVPlayerViewController,
    ) {
        onDidStop?.invoke()
    }

    override fun playerViewController(
        playerViewController: AVPlayerViewController,
        restoreUserInterfaceForPictureInPictureStopWithCompletionHandler: (Boolean) -> Unit,
    ) {
        val handler = onRestoreUi
        if (handler != null) {
            handler(playerViewController, restoreUserInterfaceForPictureInPictureStopWithCompletionHandler)
        } else {
            restoreUserInterfaceForPictureInPictureStopWithCompletionHandler(false)
        }
    }
}

/**
 * Observe modal dismissal without subclassing [AVPlayerViewController] (Apple forbids subclassing it,
 * and Kotlin/Native rejects calling its non-designated parameterless initializer as a super constructor).
 */
private class PlayerDismissalObserver(
    var onClosed: (() -> Unit)?,
) : NSObject(), UIViewControllerTransitioningDelegateProtocol {
    override fun animationControllerForDismissedController(
        dismissed: UIViewController,
    ): UIViewControllerAnimatedTransitioningProtocol? {
        val coordinator = dismissed.transitionCoordinator
        if (coordinator != null) {
            coordinator.animateAlongsideTransition(animation = null) { context ->
                if (context?.isCancelled() != true) onClosed?.invoke()
            }
        } else {
            onClosed?.invoke()
        }
        return null
    }
}

private fun presentPlaybackError(message: String) {
    val host = IosHostBridge.topViewController() ?: return
    val dialog = UIAlertController.alertControllerWithTitle(
        title = null,
        message = message,
        preferredStyle = UIAlertControllerStyleAlert,
    )
    dialog.addAction(
        UIAlertAction.actionWithTitle(
            tr("确定", "OK", "OK", "확인", "確定"),
            UIAlertActionStyleDefault,
            handler = null,
        ),
    )
    host.presentViewController(dialog, animated = true, completion = null)
}

private const val TAG = "Player"
