@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package dev.piko.ui.ios

import dev.piko.shared.log.logFailure
import dev.piko.shared.media.PreparedPlayback
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
import platform.UIKit.UIApplication
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController
import kotlin.coroutines.resume

/** The process-owned session serializes UIKit state on the main dispatcher. */
fun iosVideoPlayerHost(services: PikoServices, scope: CoroutineScope): VideoPlayerHost.Detached {
    val session = PlayerSession(services, scope)
    scope.launchOnAccountLeave(services.clientManager) { session.close() }
    return VideoPlayerHost.Detached(
        open = session::open,
        openClipFeed = { },
        isClipFeedOpen = { false },
        closeClipFeed = { },
    )
}

private class PlayerSession(private val services: PikoServices, private val scope: CoroutineScope) {
    private var generation = 0L
    private var preparing: Job? = null
    private var playback: PreparedPlayback? = null
    private var controller: ClosingPlayerController? = null

    fun close() {
        generation++
        preparing?.cancel()
        preparing = null
        releasePlayer()?.dismissViewControllerAnimated(false, completion = null)
    }

    /** Detach the native input before closing the proxy it was reading. */
    private fun releasePlayer(): ClosingPlayerController? {
        val previous = controller
        controller = null
        previous?.onClosed = null
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
                val url = if (!request.localPath.isNullOrBlank()) {
                    NSURL.fileURLWithPath(request.localPath)
                } else {
                    prepared = services.mediaRepository.preparePlayback(request.fileId)
                        .logFailure("IosPlayer", "Prepare playback failed").getOrNull()
                    prepared?.proxyUrl?.let { NSURL.URLWithString(it) }
                }
                // A stale preparation never acquires ownership of a newly opened player.
                if (url == null || ticket != generation || account != services.clientManager.currentClient.value?.account) return@launch
                val host = topViewController() ?: return@launch
                val next = ClosingPlayerController()
                next.player = AVPlayer.playerWithURL(url)
                next.modalPresentationStyle = UIModalPresentationFullScreen
                next.onClosed = {
                    if (controller === next) {
                        generation++
                        releasePlayer()
                    }
                }
                controller = next
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

private class ClosingPlayerController : AVPlayerViewController() {
    var onClosed: (() -> Unit)? = null

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        onClosed?.invoke()
    }
}

private fun topViewController(): UIViewController? {
    var controller = UIApplication.sharedApplication.keyWindow?.rootViewController ?: return null
    while (true) {
        val presented = controller.presentedViewController ?: return controller
        controller = presented
    }
}
