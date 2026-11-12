@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.piko.ui.ios

import dev.piko.shared.i18n.tr
import dev.piko.shared.log.PikoLog
import dev.piko.shared.log.logFailure
import dev.piko.shared.log.logFile
import dev.piko.shared.media.PreparedPlayback
import dev.piko.shared.notice.PikoErrorMapper
import dev.piko.shared.state.launchOnAccountLeave
import dev.piko.ui.PikoServices
import dev.piko.ui.VideoPlayerHost
import dev.piko.ui.VideoPlayerRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.*
import platform.AVKit.AVPlayerViewController
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSURL
import platform.UIKit.UIAlertAction
import platform.UIKit.UIAlertActionStyleDefault
import platform.UIKit.UIAlertController
import platform.UIKit.UIAlertControllerStyleAlert
import platform.UIKit.UIApplication
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController
import platform.UIKit.UIViewControllerAnimatedTransitioningProtocol
import platform.UIKit.UIViewControllerTransitioningDelegateProtocol
import platform.UIKit.transitionCoordinator
import platform.UIKit.transitioningDelegate
import platform.darwin.NSObject
import kotlin.coroutines.resume

/** The process-owned session serializes UIKit state on the main dispatcher. */
fun iosVideoPlayerHost(services: PikoServices, scope: CoroutineScope): VideoPlayerHost.Detached {
    val session = PlayerSession(services, scope)
    scope.launchOnAccountLeave(services.clientManager) { session.close() }
    return VideoPlayerHost.Detached(
        open = session::open,
        openClipFeed = { PikoLog.d(TAG, "Detached clip feed window is not supported on iOS") },
        isClipFeedOpen = { false },
        closeClipFeed = { },
        hasActivePlayer = session::isActive,
    )
}

private class PlayerSession(private val services: PikoServices, private val scope: CoroutineScope) {
    private var generation = 0L
    private var preparing: Job? = null
    private var playback: PreparedPlayback? = null
    private var controller: AVPlayerViewController? = null
    private var dismissalObserver: PlayerDismissalObserver? = null

    fun isActive(): Boolean = controller != null || preparing?.isActive == true

    fun close() {
        generation++
        preparing?.cancel()
        preparing = null
        releasePlayer()?.dismissViewControllerAnimated(false, completion = null)
    }

    /** Detach the native input before closing the proxy it was reading. */
    private fun releasePlayer(): AVPlayerViewController? {
        val previous = controller
        controller = null
        dismissalObserver?.onClosed = null
        dismissalObserver = null
        previous?.transitioningDelegate = null
        previous?.player?.pause()
        previous?.player?.replaceCurrentItemWithPlayerItem(null)
        previous?.player = null
        playback?.close()
        playback = null
        return previous
    }

    fun open(request: VideoPlayerRequest) {
        val ticket = ++generation
        val account = services.clientManager.currentClient.value?.account
        preparing?.cancel()
        preparing = scope.launch(Dispatchers.Main.immediate) {
            var prepared: PreparedPlayback? = null
            try {
                releasePlayer()?.let { previous ->
                    suspendCancellableCoroutine<Unit> { continuation ->
                        previous.dismissViewControllerAnimated(false) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    }
                }
                val targetLog = logFile(request.fileId, request.fileName)
                val url = if (!request.localPath.isNullOrBlank()) {
                    NSURL.fileURLWithPath(request.localPath)
                } else {
                    val result = services.mediaRepository.preparePlayback(request.fileId)
                        .logFailure(TAG, "Failed to prepare playback for $targetLog")
                    if (ticket != generation || account != services.clientManager.currentClient.value?.account) return@launch
                    prepared = result.getOrNull()
                    // Audio files and unindexed videos without a GCID bypass the loopback proxy and expose a direct CDN URL.
                    val rawUrl = (prepared?.proxyUrl ?: prepared?.info?.currentUrl)?.takeIf { it.isNotBlank() }
                    if (rawUrl == null) {
                        if (result.isSuccess) {
                            PikoLog.w(TAG, "No playable URL available for $targetLog")
                        }
                        val message = result.exceptionOrNull()?.let {
                            PikoErrorMapper.failure(tr("播放", "Play", "再生", "재생", "播放"), it)
                        } ?: tr("无法播放此文件", "Unable to play this file", "このファイルを再生できません", "이 파일을 재생할 수 없습니다", "無法播放此檔案")
                        presentPlaybackError(message)
                        return@launch
                    }
                    NSURL.URLWithString(rawUrl)
                }
                // A stale preparation never acquires ownership of a newly opened player.
                if (ticket != generation || account != services.clientManager.currentClient.value?.account) return@launch
                if (url == null) {
                    PikoLog.w(TAG, "Invalid playback URL for $targetLog")
                    presentPlaybackError(tr("无法播放此文件", "Unable to play this file", "このファイルを再生できません", "이 파일을 재생할 수 없습니다", "無法播放此檔案"))
                    return@launch
                }
                val host = topViewController()
                if (host == null) {
                    PikoLog.w(TAG, "Cannot present player for $targetLog: no active UIViewController")
                    return@launch
                }
                val next = AVPlayerViewController()
                next.player = AVPlayer.playerWithURL(url)
                next.modalPresentationStyle = UIModalPresentationFullScreen
                val observer = PlayerDismissalObserver {
                    if (controller === next) {
                        generation++
                        releasePlayer()
                    }
                }
                next.transitioningDelegate = observer
                controller = next
                dismissalObserver = observer
                playback = prepared
                prepared = null
                host.presentViewController(next, animated = true, completion = null)
                val start = request.startMillis?.coerceAtLeast(0L) ?: 0L
                if (start > 0L) next.player?.seekToTime(CMTimeMake(start, 1000))
                next.player?.play()
            } finally {
                // Includes missing proxy URLs, cancelled preparations, and unavailable UIKit hosts.
                prepared?.close()
            }
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

private fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
    while (true) {
        val presented = controller.presentedViewController ?: return controller
        controller = presented
    }
}

private fun presentPlaybackError(message: String) {
    val host = topViewController() ?: return
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
